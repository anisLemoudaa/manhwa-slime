-- Reading-for-coins tasks. Additive only: apply through the normal migration pipeline.
-- The server validates canonical local catalog keys, elapsed time, idempotency and caps.
-- The source catalog itself is on-device, so this is anti-abuse mitigation, not cryptographic proof.

create table if not exists public.reading_task_definitions (
    id text primary key check (id ~ '^[a-z0-9][a-z0-9_-]{0,79}$'),
    title text not null check (length(title) between 1 and 160),
    description text not null default '' check (length(description) <= 500),
    reward_coins integer not null check (reward_coins > 0 and reward_coins <= 100000),
    target_count integer not null check (target_count > 0 and target_count <= 1000),
    min_elapsed_seconds integer not null default 20 check (min_elapsed_seconds between 5 and 86400),
    active boolean not null default true,
    sort_order integer not null default 0,
    starts_at timestamptz,
    ends_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (ends_at is null or starts_at is null or ends_at > starts_at)
);

create table if not exists public.reading_task_settings (
    id boolean primary key default true check (id),
    max_daily_completions integer not null default 50 check (max_daily_completions between 1 and 10000),
    updated_at timestamptz not null default now()
);

create table if not exists public.reading_task_sessions (
    id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    chapter_key text not null check (length(chapter_key) between 9 and 100 and chapter_key ~ '^manga:[0-9]+:[0-9]+$'),
    started_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '24 hours'),
    required_elapsed_seconds integer not null check (required_elapsed_seconds between 5 and 86400),
    status text not null default 'open' check (status in ('open', 'completed', 'rejected', 'capped')),
    completed_at timestamptz,
    elapsed_seconds integer,
    created_at timestamptz not null default now()
);
create index if not exists reading_task_sessions_user_started_idx
    on public.reading_task_sessions(user_id, started_at desc);
create unique index if not exists reading_task_sessions_one_open_chapter_idx
    on public.reading_task_sessions(user_id, chapter_key) where status = 'open';

create table if not exists public.reading_task_completions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    chapter_key text not null check (length(chapter_key) between 9 and 100 and chapter_key ~ '^manga:[0-9]+:[0-9]+$'),
    session_id uuid not null references public.reading_task_sessions(id) on delete restrict,
    completion_date date not null,
    completed_at timestamptz not null default now(),
    unique (user_id, chapter_key, completion_date),
    unique (session_id)
);
create index if not exists reading_task_completions_user_date_idx
    on public.reading_task_completions(user_id, completion_date);

create table if not exists public.reading_task_claims (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    task_id text not null references public.reading_task_definitions(id) on delete restrict,
    claim_date date not null,
    reward_coins integer not null check (reward_coins > 0),
    claimed_at timestamptz not null default now(),
    unique (user_id, task_id, claim_date)
);
create index if not exists reading_task_claims_user_date_idx
    on public.reading_task_claims(user_id, claim_date);

-- Preserve all live transaction kinds, including admin_grant, and add the new server-only kind.
alter table public.coin_transactions
    drop constraint if exists coin_transactions_kind_check;
alter table public.coin_transactions
    add constraint coin_transactions_kind_check
    check (kind in (
        'ad_reward', 'play_purchase', 'download', 'download_refund', 'vip_purchase',
        'daily_login_reward', 'welcome_bonus', 'shop_purchase', 'admin_grant',
        'reading_task_reward'
    ));

alter table public.reading_task_definitions enable row level security;
alter table public.reading_task_settings enable row level security;
alter table public.reading_task_sessions enable row level security;
alter table public.reading_task_completions enable row level security;
alter table public.reading_task_claims enable row level security;
revoke all on public.reading_task_definitions, public.reading_task_settings,
    public.reading_task_sessions, public.reading_task_completions, public.reading_task_claims
    from anon, authenticated;

insert into public.reading_task_settings(id, max_daily_completions)
values (true, 50)
on conflict (id) do nothing;

-- Reward amounts live in this server-owned table and can be adjusted without an app release.
insert into public.reading_task_definitions
    (id, title, description, reward_coins, target_count, min_elapsed_seconds, active, sort_order)
values
    ('daily_read_1', 'بداية القراءة', 'أكمل قراءة فصل مانهوا واحد اليوم.', 3, 1, 20, true, 10),
    ('daily_read_3', 'قارئ نشيط', 'أكمل قراءة 3 فصول مانهوا مختلفة اليوم.', 10, 3, 20, true, 20),
    ('daily_read_5', 'قارئ محترف', 'أكمل قراءة 5 فصول مانهوا مختلفة اليوم.', 20, 5, 20, true, 30)
on conflict (id) do nothing;

create or replace function public.begin_reading_task_session(p_session_id uuid, p_chapter_key text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_existing public.reading_task_sessions%rowtype;
    v_session_id uuid;
    v_required integer;
begin
    if v_user is null then raise exception 'authentication required' using errcode = '42501'; end if;
    if p_session_id is null then raise exception 'session id required'; end if;
    if length(coalesce(p_chapter_key, '')) not between 9 and 100
       or coalesce(p_chapter_key, '') !~ '^manga:[0-9]+:[0-9]+$' then
        raise exception 'invalid canonical chapter key';
    end if;

    select coalesce(greatest(max(d.min_elapsed_seconds), 20), 20)
      into v_required
      from public.reading_task_definitions d
     where d.active
       and (d.starts_at is null or d.starts_at <= now())
       and (d.ends_at is null or d.ends_at > now());

    update public.reading_task_sessions
       set status = 'rejected', completed_at = now()
     where user_id = v_user and status = 'open' and expires_at <= now();

    select * into v_existing
      from public.reading_task_sessions
     where id = p_session_id
     for update;
    if found then
        if v_existing.user_id <> v_user or v_existing.chapter_key <> p_chapter_key then
            raise exception 'session belongs to another user or chapter' using errcode = '42501';
        end if;
        return jsonb_build_object('session_id', v_existing.id, 'started_at', v_existing.started_at,
                                  'status', v_existing.status);
    end if;

    -- A new reader lifecycle gets a fresh server start time; it cannot reuse a stale open session.
    update public.reading_task_sessions
       set status = 'rejected', completed_at = now()
     where user_id = v_user and chapter_key = p_chapter_key and status = 'open';

    insert into public.reading_task_sessions(id, user_id, chapter_key, required_elapsed_seconds)
    values (p_session_id, v_user, p_chapter_key, v_required)
    on conflict do nothing
    returning id into v_session_id;
    if v_session_id is null then
        select id into v_session_id
          from public.reading_task_sessions
         where user_id = v_user and chapter_key = p_chapter_key and status = 'open'
         order by started_at desc limit 1;
    end if;
    if v_session_id is null then raise exception 'could not start reading session'; end if;
    return jsonb_build_object('session_id', v_session_id, 'started_at', now(), 'status', 'open');
end;
$$;

create or replace function public.complete_reading_task_session(p_session_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_session public.reading_task_sessions%rowtype;
    v_elapsed integer;
    v_cap integer;
    v_day date := (now() at time zone 'UTC')::date;
    v_inserted uuid;
begin
    if v_user is null then raise exception 'authentication required' using errcode = '42501'; end if;
    select * into v_session
      from public.reading_task_sessions
     where id = p_session_id and user_id = v_user
     for update;
    if not found then raise exception 'reading session not found'; end if;
    if v_session.status = 'completed' then
        return jsonb_build_object('accepted', true, 'duplicate', true, 'chapter_key', v_session.chapter_key);
    end if;
    if v_session.status in ('rejected', 'capped') then
        return jsonb_build_object('accepted', false, 'status', v_session.status);
    end if;

    v_elapsed := greatest(0, floor(extract(epoch from (now() - v_session.started_at)))::integer);
    if now() > v_session.expires_at or v_elapsed < v_session.required_elapsed_seconds then
        update public.reading_task_sessions
           set status = 'rejected', elapsed_seconds = v_elapsed, completed_at = now()
         where id = v_session.id;
        return jsonb_build_object('accepted', false, 'status', 'min_elapsed', 'elapsed_seconds', v_elapsed);
    end if;

    -- Serialize only this user's daily cap checks; balances/completions remain per-user atomic.
    perform pg_advisory_xact_lock(hashtext(v_user::text), 982701);
    select max_daily_completions into v_cap
      from public.reading_task_settings where id = true;
    v_cap := coalesce(v_cap, 50);
    if (select count(*) from public.reading_task_completions
         where user_id = v_user and completion_date = v_day) >= v_cap then
        update public.reading_task_sessions
           set status = 'capped', elapsed_seconds = v_elapsed, completed_at = now()
         where id = v_session.id;
        return jsonb_build_object('accepted', false, 'status', 'daily_cap');
    end if;

    insert into public.reading_task_completions(user_id, chapter_key, session_id, completion_date)
    values (v_user, v_session.chapter_key, v_session.id, v_day)
    on conflict (user_id, chapter_key, completion_date) do nothing
    returning id into v_inserted;
    update public.reading_task_sessions
       set status = 'completed', elapsed_seconds = v_elapsed, completed_at = now()
     where id = v_session.id;
    return jsonb_build_object('accepted', true, 'duplicate', v_inserted is null,
                              'chapter_key', v_session.chapter_key);
end;
$$;

create or replace function public.reading_tasks_for_today()
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_date date := (now() at time zone 'UTC')::date;
    v_tasks jsonb;
begin
    if v_user is null then raise exception 'authentication required' using errcode = '42501'; end if;
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', d.id,
        'title', d.title,
        'description', d.description,
        'reward_coins', d.reward_coins,
        'target_count', d.target_count,
        'progress', least(d.target_count, coalesce(p.progress, 0)),
        'claimed', (c.id is not null),
        'claimable', (c.id is null and coalesce(p.progress, 0) >= d.target_count)
    ) order by d.sort_order, d.id), '[]'::jsonb)
      into v_tasks
      from public.reading_task_definitions d
      left join (
          select count(distinct r.chapter_key)::integer as progress
            from public.reading_task_completions r
           where r.user_id = v_user and r.completion_date = v_date
      ) p on true
      left join public.reading_task_claims c
        on c.user_id = v_user and c.task_id = d.id and c.claim_date = v_date
     where d.active
       and (d.starts_at is null or d.starts_at <= now())
       and (d.ends_at is null or d.ends_at > now());
    return jsonb_build_object('date', v_date, 'tasks', v_tasks);
end;
$$;

create or replace function public.claim_reading_task(p_task_id text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_task public.reading_task_definitions%rowtype;
    v_progress integer;
    v_day date := (now() at time zone 'UTC')::date;
    v_claim_id uuid;
    v_balance integer;
    v_tx_id uuid;
begin
    if v_user is null then raise exception 'authentication required' using errcode = '42501'; end if;
    select * into v_task from public.reading_task_definitions
     where id = p_task_id and active
       and (starts_at is null or starts_at <= now())
       and (ends_at is null or ends_at > now())
     for update;
    if not found then raise exception 'task is not available'; end if;
    select count(distinct chapter_key)::integer into v_progress
      from public.reading_task_completions
     where user_id = v_user and completion_date = v_day;
    if v_progress < v_task.target_count then raise exception 'task is not complete'; end if;

    insert into public.reading_task_claims(user_id, task_id, claim_date, reward_coins)
    values (v_user, v_task.id, v_day, v_task.reward_coins)
    on conflict (user_id, task_id, claim_date) do nothing
    returning id into v_claim_id;
    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    select balance into v_balance from public.coin_wallets where user_id = v_user for update;
    if v_claim_id is null then
        return jsonb_build_object('claimed', false, 'already_claimed', true, 'balance', v_balance);
    end if;

    update public.coin_wallets
       set balance = balance + v_task.reward_coins, updated_at = now()
     where user_id = v_user;
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
    values (v_user, v_task.reward_coins, 'reading_task_reward',
            'reading_task:' || v_task.id || ':' || v_day::text, v_task.id)
    on conflict (user_id, idempotency_key) do nothing
    returning id into v_tx_id;
    if v_tx_id is null then
        -- Compensating guard for inconsistent pre-existing ledger data; normal path is atomic.
        update public.coin_wallets set balance = balance - v_task.reward_coins, updated_at = now()
         where user_id = v_user;
    end if;
    select balance into v_balance from public.coin_wallets where user_id = v_user;
    return jsonb_build_object('claimed', true, 'already_claimed', false,
                              'reward_coins', v_task.reward_coins, 'balance', v_balance);
end;
$$;

revoke all on function public.begin_reading_task_session(uuid, text) from public, anon;
revoke all on function public.complete_reading_task_session(uuid) from public, anon;
revoke all on function public.reading_tasks_for_today() from public, anon;
revoke all on function public.claim_reading_task(text) from public, anon;
grant execute on function public.begin_reading_task_session(uuid, text) to authenticated;
grant execute on function public.complete_reading_task_session(uuid) to authenticated;
grant execute on function public.reading_tasks_for_today() to authenticated;
grant execute on function public.claim_reading_task(text) to authenticated;
