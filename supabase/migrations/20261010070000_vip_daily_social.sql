-- Additive VIP, daily login rewards, and in-app social metrics.
-- Existing wallet balances, ledger rows, comments, votes, and reservations are preserved.

alter table public.coin_transactions
    drop constraint if exists coin_transactions_kind_check;
alter table public.coin_transactions
    add constraint coin_transactions_kind_check
    check (kind in ('ad_reward', 'play_purchase', 'download', 'download_refund', 'vip_purchase', 'daily_login_reward'));

alter table public.coin_download_reservations
    add column if not exists vip_free boolean not null default false;

create table if not exists public.vip_memberships (
    user_id uuid primary key references auth.users(id) on delete cascade,
    plan text not null check (plan in ('month', 'six_months', 'lifetime')),
    started_at timestamptz not null default now(),
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check ((plan = 'lifetime' and expires_at is null) or (plan <> 'lifetime' and expires_at is not null))
);

create table if not exists public.daily_login_state (
    user_id uuid primary key references auth.users(id) on delete cascade,
    last_claim_day date,
    streak_day smallint not null default 0 check (streak_day between 0 and 7),
    updated_at timestamptz not null default now()
);

create table if not exists public.title_views (
    title_key text not null check (length(title_key) between 1 and 160),
    user_id uuid not null references auth.users(id) on delete cascade,
    view_day date not null,
    created_at timestamptz not null default now(),
    primary key (title_key, user_id, view_day)
);
create index if not exists title_views_title_key_idx on public.title_views(title_key);

create table if not exists public.title_ratings (
    title_key text not null check (length(title_key) between 1 and 160),
    user_id uuid not null references auth.users(id) on delete cascade,
    rating smallint not null check (rating between 1 and 5),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (title_key, user_id)
);
create index if not exists title_ratings_title_key_idx on public.title_ratings(title_key);

alter table public.vip_memberships enable row level security;
alter table public.daily_login_state enable row level security;
alter table public.title_views enable row level security;
alter table public.title_ratings enable row level security;
revoke all on public.vip_memberships, public.daily_login_state, public.title_views, public.title_ratings from anon, authenticated;

create or replace function public.get_my_vip_status()
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_membership public.vip_memberships%rowtype;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    select * into v_membership from public.vip_memberships where user_id = v_user;
    if not found or (v_membership.expires_at is not null and v_membership.expires_at <= now()) then
        return jsonb_build_object('active', false, 'plan', null, 'expires_at', null);
    end if;
    return jsonb_build_object(
        'active', true,
        'plan', v_membership.plan,
        'expires_at', v_membership.expires_at,
        'started_at', v_membership.started_at
    );
end;
$$;

create or replace function public.purchase_vip(p_plan text, p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_price integer;
    v_balance integer;
    v_now timestamptz := now();
    v_started timestamptz;
    v_expiry timestamptz;
    v_current public.vip_memberships%rowtype;
    v_existing public.coin_transactions%rowtype;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if p_request_id is null then raise exception 'request id required'; end if;
    v_price := case p_plan when 'month' then 500 when 'six_months' then 1600 when 'lifetime' then 8000 else null end;
    if v_price is null then raise exception 'invalid VIP plan'; end if;

    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    select balance into v_balance from public.coin_wallets where user_id = v_user for update;
    select * into v_existing from public.coin_transactions
      where user_id = v_user and idempotency_key = 'vip:' || p_request_id::text;
    if found then
        select * into v_current from public.vip_memberships where user_id = v_user;
        return jsonb_build_object('success', true, 'already_processed', true, 'plan', v_current.plan,
            'expires_at', v_current.expires_at, 'balance', v_balance);
    end if;

    select * into v_current from public.vip_memberships where user_id = v_user for update;
    if found and v_current.plan = 'lifetime' and v_current.expires_at is null then
        return jsonb_build_object('success', false, 'reason', 'lifetime_active', 'balance', v_balance,
            'plan', v_current.plan, 'expires_at', v_current.expires_at);
    end if;
    if v_balance < v_price then
        return jsonb_build_object('success', false, 'reason', 'insufficient_coins', 'balance', v_balance,
            'required', v_price);
    end if;

    if not found or (v_current.expires_at is not null and v_current.expires_at <= v_now) then
        v_started := v_now;
        v_expiry := case p_plan
            when 'month' then v_now + interval '1 month'
            when 'six_months' then v_now + interval '6 months'
            else null end;
    else
        v_started := v_current.started_at;
        v_expiry := case p_plan
            when 'month' then greatest(v_current.expires_at, v_now) + interval '1 month'
            when 'six_months' then greatest(v_current.expires_at, v_now) + interval '6 months'
            else null end;
    end if;

    update public.coin_wallets set balance = balance - v_price, updated_at = v_now where user_id = v_user;
    insert into public.vip_memberships(user_id, plan, started_at, expires_at, updated_at)
        values (v_user, p_plan, v_started, v_expiry, v_now)
    on conflict (user_id) do update set plan = excluded.plan, started_at = excluded.started_at,
        expires_at = excluded.expires_at, updated_at = excluded.updated_at;
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
        values (v_user, -v_price, 'vip_purchase', 'vip:' || p_request_id::text, p_plan);
    select balance into v_balance from public.coin_wallets where user_id = v_user;
    return jsonb_build_object('success', true, 'already_processed', false, 'plan', p_plan,
        'expires_at', v_expiry, 'balance', v_balance, 'price', v_price);
end;
$$;

create or replace function public.claim_daily_login_reward()
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_today date := (now() at time zone 'UTC')::date;
    v_last date;
    v_day smallint;
    v_reward integer;
    v_balance integer;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    insert into public.daily_login_state(user_id) values (v_user) on conflict (user_id) do nothing;
    select last_claim_day, streak_day into v_last, v_day
        from public.daily_login_state where user_id = v_user for update;
    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    select balance into v_balance from public.coin_wallets where user_id = v_user for update;

    if v_last = v_today then
        return jsonb_build_object('success', true, 'claimed', false, 'already_claimed', true,
            'day', v_day, 'reward', 0, 'balance', v_balance);
    end if;
    if v_last = v_today - 1 then
        v_day := case when v_day >= 7 then 1 else v_day + 1 end;
    else
        v_day := 1;
    end if;
    v_reward := case v_day when 1 then 30 when 2 then 45 when 3 then 60 when 4 then 90
        when 5 then 120 when 6 then 165 when 7 then 200 end;

    update public.coin_wallets set balance = balance + v_reward, updated_at = now() where user_id = v_user;
    update public.daily_login_state set last_claim_day = v_today, streak_day = v_day, updated_at = now()
        where user_id = v_user;
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
        values (v_user, v_reward, 'daily_login_reward', 'daily_login:' || v_today::text,
            v_day::text || ':' || v_reward::text);
    select balance into v_balance from public.coin_wallets where user_id = v_user;
    return jsonb_build_object('success', true, 'claimed', true, 'already_claimed', false,
        'day', v_day, 'reward', v_reward, 'balance', v_balance, 'claim_day', v_today);
end;
$$;

create or replace function public.record_title_view(p_title_key text)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_count bigint;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if length(coalesce(p_title_key, '')) not between 1 and 160 then raise exception 'invalid title key'; end if;
    insert into public.title_views(title_key, user_id, view_day)
        values (p_title_key, v_user, (now() at time zone 'UTC')::date)
        on conflict (title_key, user_id, view_day) do nothing;
    select count(*) into v_count from public.title_views where title_key = p_title_key;
    return v_count::integer;
end;
$$;

create or replace function public.get_title_metrics(p_title_key text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_average numeric;
    v_rating_count bigint;
    v_views bigint;
    v_my_rating smallint;
begin
    if length(coalesce(p_title_key, '')) not between 1 and 160 then raise exception 'invalid title key'; end if;
    select round(avg(rating)::numeric, 1), count(*) into v_average, v_rating_count
        from public.title_ratings where title_key = p_title_key;
    select count(*) into v_views from public.title_views where title_key = p_title_key;
    select rating into v_my_rating from public.title_ratings
        where title_key = p_title_key and user_id = auth.uid();
    return jsonb_build_object('average', coalesce(v_average, 0), 'rating_count', coalesce(v_rating_count, 0),
        'views', coalesce(v_views, 0), 'my_rating', v_my_rating);
end;
$$;

create or replace function public.rate_title(p_title_key text, p_rating integer)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if length(coalesce(p_title_key, '')) not between 1 and 160 then raise exception 'invalid title key'; end if;
    if p_rating not between 1 and 5 then raise exception 'rating must be from 1 to 5'; end if;
    insert into public.title_ratings(title_key, user_id, rating)
        values (p_title_key, v_user, p_rating)
        on conflict (title_key, user_id) do update set rating = excluded.rating, updated_at = now();
    return public.get_title_metrics(p_title_key);
end;
$$;

create or replace function public.list_comments_with_vip(p_title_key text, p_sort integer default 0)
returns table (
    id uuid,
    user_id uuid,
    author_name text,
    body text,
    created_at timestamptz,
    is_spoiler boolean,
    level integer,
    rank text,
    author_avatar text,
    likes integer,
    dislikes integer,
    is_vip boolean,
    is_pinned boolean
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with visible_comments as (
        select c.id, c.user_id, c.author_name, c.body, c.created_at, c.is_spoiler, c.level, c.rank,
            c.author_avatar, c.likes, c.dislikes,
            coalesce(m.expires_at is null or m.expires_at > now(), false) as member_vip,
            row_number() over (partition by c.user_id order by c.created_at desc, c.id desc) as vip_position
        from public.comments c
        left join public.vip_memberships m on m.user_id = c.user_id
        where c.title_key = p_title_key and c.hidden = false
    )
    select v.id, v.user_id, v.author_name, v.body, v.created_at, v.is_spoiler, v.level, v.rank,
        v.author_avatar, v.likes, v.dislikes, v.member_vip,
        (v.member_vip and v.vip_position = 1) as is_pinned
    from visible_comments v
    order by (v.member_vip and v.vip_position = 1) desc,
        case when p_sort = 1 then v.likes end desc nulls last,
        v.created_at desc
    limit 50;
$$;

create or replace function public.reserve_download_coin(p_reservation_id uuid, p_chapter_key text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_balance integer;
    v_status text;
    v_existing_chapter_key text;
    v_vip_free boolean;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if length(trim(coalesce(p_chapter_key, ''))) = 0 then raise exception 'chapter key required'; end if;
    if length(p_chapter_key) > 512 then raise exception 'chapter key is too long'; end if;
    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;

    select status, chapter_key, vip_free into v_status, v_existing_chapter_key, v_vip_free
      from public.coin_download_reservations where id = p_reservation_id and user_id = v_user for update;
    if v_status is not null then
        if v_existing_chapter_key <> p_chapter_key then raise exception 'reservation belongs to another chapter'; end if;
        select balance into v_balance from public.coin_wallets where user_id = v_user;
        return jsonb_build_object('allowed', v_status = 'reserved', 'balance', v_balance,
            'status', case when v_vip_free and v_status = 'reserved' then 'vip' else v_status end);
    end if;

    select balance into v_balance from public.coin_wallets where user_id = v_user for update;
    if exists (select 1 from public.vip_memberships where user_id = v_user
        and (expires_at is null or expires_at > now())) then
        insert into public.coin_download_reservations(id, user_id, chapter_key, vip_free)
            values (p_reservation_id, v_user, p_chapter_key, true);
        return jsonb_build_object('allowed', true, 'balance', v_balance, 'status', 'vip');
    end if;

    if v_balance < 1 then
        return jsonb_build_object('allowed', false, 'balance', v_balance, 'status', 'insufficient');
    end if;
    update public.coin_wallets set balance = balance - 1, updated_at = now() where user_id = v_user;
    insert into public.coin_download_reservations(id, user_id, chapter_key)
      values (p_reservation_id, v_user, p_chapter_key);
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
      values (v_user, -1, 'download', 'download:' || p_reservation_id::text, p_chapter_key);
    select balance into v_balance from public.coin_wallets where user_id = v_user;
    return jsonb_build_object('allowed', true, 'balance', v_balance, 'status', 'reserved');
end;
$$;

create or replace function public.refund_download_coin(p_reservation_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_status text;
    v_vip_free boolean;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    select status, vip_free into v_status, v_vip_free from public.coin_download_reservations
      where id = p_reservation_id and user_id = v_user for update;
    if v_status is null then return false; end if;
    if v_status = 'refunded' then return true; end if;
    if v_status <> 'reserved' then return false; end if;
    update public.coin_download_reservations set status = 'refunded', updated_at = now()
      where id = p_reservation_id and user_id = v_user;
    if not v_vip_free then
        update public.coin_wallets set balance = balance + 1, updated_at = now() where user_id = v_user;
        insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
          values (v_user, 1, 'download_refund', 'refund:' || p_reservation_id::text, p_reservation_id::text)
          on conflict (user_id, idempotency_key) do nothing;
    end if;
    return true;
end;
$$;

revoke all on function public.get_my_vip_status() from public, anon;
revoke all on function public.purchase_vip(text, uuid) from public, anon;
revoke all on function public.claim_daily_login_reward() from public, anon;
revoke all on function public.record_title_view(text) from public, anon;
revoke all on function public.get_title_metrics(text) from public;
revoke all on function public.rate_title(text, integer) from public, anon;
revoke all on function public.list_comments_with_vip(text, integer) from public;
revoke all on function public.reserve_download_coin(uuid, text) from public, anon;
revoke all on function public.refund_download_coin(uuid) from public, anon;
grant execute on function public.get_my_vip_status() to authenticated;
grant execute on function public.purchase_vip(text, uuid) to authenticated;
grant execute on function public.claim_daily_login_reward() to authenticated;
grant execute on function public.record_title_view(text) to authenticated;
grant execute on function public.get_title_metrics(text) to anon, authenticated;
grant execute on function public.rate_title(text, integer) to authenticated;
grant execute on function public.list_comments_with_vip(text, integer) to anon, authenticated;
grant execute on function public.reserve_download_coin(uuid, text) to authenticated;
grant execute on function public.refund_download_coin(uuid) to authenticated;
