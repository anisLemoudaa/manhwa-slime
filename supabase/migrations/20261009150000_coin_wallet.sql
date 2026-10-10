-- Coin wallet backend for Manhwa Slime.
-- Apply with Supabase CLI after linking the project. Never expose service-role credentials in the app.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.coin_wallets (
    user_id uuid primary key references auth.users(id) on delete cascade,
    balance integer not null default 0 check (balance >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.coin_transactions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    delta integer not null check (delta <> 0),
    kind text not null check (kind in ('ad_reward', 'play_purchase', 'download', 'download_refund')),
    idempotency_key text not null,
    reference_id text,
    created_at timestamptz not null default now(),
    unique (user_id, idempotency_key)
);

create unique index if not exists coin_transactions_ad_reward_reference_uidx
    on public.coin_transactions(reference_id)
    where kind = 'ad_reward' and reference_id is not null;

create table if not exists public.coin_download_reservations (
    id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    chapter_key text not null,
    status text not null default 'reserved' check (status in ('reserved', 'committed', 'refunded')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.ad_reward_sessions (
    id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    status text not null default 'pending' check (status in ('pending', 'credited', 'expired')),
    admob_transaction_id text unique,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '24 hours'),
    credited_at timestamptz
);

create table if not exists public.coin_purchase_receipts (
    purchase_token text primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    product_id text not null,
    coin_amount integer not null check (coin_amount in (15, 40, 90, 200)),
    created_at timestamptz not null default now()
);

alter table public.coin_wallets enable row level security;
alter table public.coin_transactions enable row level security;
alter table public.coin_download_reservations enable row level security;
alter table public.ad_reward_sessions enable row level security;
alter table public.coin_purchase_receipts enable row level security;

-- The app can read its own balance/history, but all balance changes go through RPCs.
drop policy if exists coin_wallets_select_own on public.coin_wallets;
create policy coin_wallets_select_own on public.coin_wallets for select to authenticated using (user_id = auth.uid());
drop policy if exists coin_transactions_select_own on public.coin_transactions;
create policy coin_transactions_select_own on public.coin_transactions for select to authenticated using (user_id = auth.uid());
drop policy if exists ad_reward_sessions_select_own on public.ad_reward_sessions;
create policy ad_reward_sessions_select_own on public.ad_reward_sessions for select to authenticated using (user_id = auth.uid());

revoke all on public.coin_wallets, public.coin_transactions, public.coin_download_reservations,
    public.ad_reward_sessions, public.coin_purchase_receipts from anon, authenticated;
grant select on public.coin_wallets, public.coin_transactions, public.ad_reward_sessions to authenticated;

create or replace function public.ensure_coin_wallet()
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_balance integer;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    select balance into v_balance from public.coin_wallets where user_id = v_user;
    return v_balance;
end;
$$;

create or replace function public.begin_ad_reward(p_session_id uuid)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then raise exception 'authentication required'; end if;
    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    insert into public.ad_reward_sessions(id, user_id)
    values (p_session_id, v_user)
    on conflict (id) do nothing;
    if not exists (
        select 1 from public.ad_reward_sessions
        where id = p_session_id and user_id = v_user and status = 'pending'
          and expires_at > now()
    ) then raise exception 'reward session is invalid'; end if;
    return p_session_id;
end;
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
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if length(trim(coalesce(p_chapter_key, ''))) = 0 then raise exception 'chapter key required'; end if;
    if length(p_chapter_key) > 512 then raise exception 'chapter key is too long'; end if;
    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;

    select status, chapter_key into v_status, v_existing_chapter_key from public.coin_download_reservations
      where id = p_reservation_id and user_id = v_user for update;
    if v_status is not null then
        if v_existing_chapter_key <> p_chapter_key then raise exception 'reservation belongs to another chapter'; end if;
        select balance into v_balance from public.coin_wallets where user_id = v_user;
        return jsonb_build_object('allowed', v_status = 'reserved', 'balance', v_balance, 'status', v_status);
    end if;

    select balance into v_balance from public.coin_wallets where user_id = v_user for update;
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

create or replace function public.commit_download_coin(p_reservation_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_status text;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    select status into v_status from public.coin_download_reservations
      where id = p_reservation_id and user_id = v_user for update;
    if v_status is null then return false; end if;
    if v_status = 'committed' then return true; end if;
    if v_status <> 'reserved' then return false; end if;
    update public.coin_download_reservations set status = 'committed', updated_at = now()
      where id = p_reservation_id and user_id = v_user;
    return true;
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
begin
    if v_user is null then raise exception 'authentication required'; end if;
    select status into v_status from public.coin_download_reservations
      where id = p_reservation_id and user_id = v_user for update;
    if v_status is null then return false; end if;
    if v_status = 'refunded' then return true; end if;
    if v_status <> 'reserved' then return false; end if;
    update public.coin_download_reservations set status = 'refunded', updated_at = now()
      where id = p_reservation_id and user_id = v_user;
    update public.coin_wallets set balance = balance + 1, updated_at = now() where user_id = v_user;
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
      values (v_user, 1, 'download_refund', 'refund:' || p_reservation_id::text, p_reservation_id::text)
      on conflict (user_id, idempotency_key) do nothing;
    return true;
end;
$$;

-- Called only by the signed AdMob SSV Edge Function (service_role).
create or replace function public.grant_ad_reward(
    p_session_id uuid,
    p_user_id uuid,
    p_transaction_id text,
    p_reward_amount integer,
    p_reward_item text
)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_session public.ad_reward_sessions%rowtype;
    v_existing_user uuid;
    v_balance integer;
begin
    if p_reward_amount <> 15 or p_reward_item <> 'gold_coins' then raise exception 'unexpected reward'; end if;
    if length(coalesce(p_transaction_id, '')) < 16 then raise exception 'invalid transaction id'; end if;
    select user_id into v_existing_user
      from public.coin_transactions
      where kind = 'ad_reward' and reference_id = p_transaction_id
      limit 1;
    if found then
        if v_existing_user <> p_user_id then raise exception 'ad reward transaction belongs to another user'; end if;
        select balance into v_balance from public.coin_wallets where user_id = p_user_id;
        return v_balance;
    end if;
    select * into v_session from public.ad_reward_sessions where id = p_session_id for update;
    if not found or v_session.user_id <> p_user_id or v_session.status <> 'pending' or v_session.expires_at <= now() then
        raise exception 'reward session is invalid or expired';
    end if;
    insert into public.coin_wallets(user_id) values (p_user_id) on conflict (user_id) do nothing;
    update public.coin_wallets set balance = balance + 15, updated_at = now() where user_id = p_user_id;
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
      values (p_user_id, 15, 'ad_reward', 'admob:' || p_transaction_id, p_transaction_id);
    update public.ad_reward_sessions set status = 'credited', admob_transaction_id = p_transaction_id, credited_at = now()
      where id = p_session_id;
    select balance into v_balance from public.coin_wallets where user_id = p_user_id;
    return v_balance;
end;
$$;

-- Called only by the receipt-verifying Edge Function (service_role).
create or replace function public.grant_play_coin_purchase(
    p_user_id uuid,
    p_product_id text,
    p_purchase_token text,
    p_coin_amount integer
)
returns integer
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_expected integer;
    v_existing public.coin_purchase_receipts%rowtype;
    v_balance integer;
begin
    v_expected := case p_product_id
      when 'gold_coins_15' then 15
      when 'gold_coins_40' then 40
      when 'gold_coins_90' then 90
      when 'gold_coins_200' then 200
      else null end;
    if v_expected is null or p_coin_amount <> v_expected or length(coalesce(p_purchase_token, '')) < 20 then
      raise exception 'purchase product or token is invalid';
    end if;
    select * into v_existing from public.coin_purchase_receipts where purchase_token = p_purchase_token;
    if found then
        if v_existing.user_id <> p_user_id or v_existing.product_id <> p_product_id then raise exception 'purchase token already used'; end if;
        select balance into v_balance from public.coin_wallets where user_id = p_user_id;
        return v_balance;
    end if;
    insert into public.coin_wallets(user_id) values (p_user_id) on conflict (user_id) do nothing;
    insert into public.coin_purchase_receipts(purchase_token, user_id, product_id, coin_amount)
      values (p_purchase_token, p_user_id, p_product_id, v_expected);
    update public.coin_wallets set balance = balance + v_expected, updated_at = now() where user_id = p_user_id;
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
      values (p_user_id, v_expected, 'play_purchase', 'play:' || encode(digest(p_purchase_token, 'sha256'), 'hex'), p_product_id);
    select balance into v_balance from public.coin_wallets where user_id = p_user_id;
    return v_balance;
end;
$$;

revoke all on function public.ensure_coin_wallet() from public, anon;
revoke all on function public.begin_ad_reward(uuid) from public, anon;
revoke all on function public.reserve_download_coin(uuid, text) from public, anon;
revoke all on function public.commit_download_coin(uuid) from public, anon;
revoke all on function public.refund_download_coin(uuid) from public, anon;
grant execute on function public.ensure_coin_wallet() to authenticated;
grant execute on function public.begin_ad_reward(uuid) to authenticated;
grant execute on function public.reserve_download_coin(uuid, text) to authenticated;
grant execute on function public.commit_download_coin(uuid) to authenticated;
grant execute on function public.refund_download_coin(uuid) to authenticated;

revoke all on function public.grant_ad_reward(uuid, uuid, text, integer, text) from public, anon, authenticated;
revoke all on function public.grant_play_coin_purchase(uuid, text, text, integer) from public, anon, authenticated;
grant execute on function public.grant_ad_reward(uuid, uuid, text, integer, text) to service_role;
grant execute on function public.grant_play_coin_purchase(uuid, text, text, integer) to service_role;
