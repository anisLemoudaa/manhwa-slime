-- Temporary welcome promotion: new Supabase auth users receive enough coins for one month of VIP.
-- Disable the promotion after AdMob approval by setting is_active=false for promo_key='new_users_while_ads_pending'.
-- Existing users and balances are intentionally not modified.

alter table public.coin_transactions
    drop constraint if exists coin_transactions_kind_check;
alter table public.coin_transactions
    add constraint coin_transactions_kind_check
    check (kind in (
        'ad_reward',
        'play_purchase',
        'download',
        'download_refund',
        'vip_purchase',
        'daily_login_reward',
        'welcome_bonus'
    ));

create table if not exists public.coin_welcome_bonus_promotions (
    promo_key text primary key,
    is_active boolean not null default true,
    coin_amount integer not null default 500 check (coin_amount = 500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table public.coin_welcome_bonus_promotions enable row level security;
revoke all on public.coin_welcome_bonus_promotions from anon, authenticated;

insert into public.coin_welcome_bonus_promotions(promo_key, is_active, coin_amount)
values ('new_users_while_ads_pending', true, 500)
on conflict (promo_key) do nothing;

create or replace function public.grant_new_user_welcome_bonus()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_active boolean;
    v_amount integer;
    v_transaction_id uuid;
begin
    select is_active, coin_amount
      into v_active, v_amount
      from public.coin_welcome_bonus_promotions
     where promo_key = 'new_users_while_ads_pending'
     for share;

    if not coalesce(v_active, false) or v_amount is null or new.id is null then
        return new;
    end if;

    insert into public.coin_wallets(user_id, balance)
    values (new.id, 0)
    on conflict (user_id) do nothing;

    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
    values (new.id, v_amount, 'welcome_bonus', 'welcome_bonus:new_users_while_ads_pending', new.id::text)
    on conflict (user_id, idempotency_key) do nothing
    returning id into v_transaction_id;

    -- Ledger insertion and balance update are in the same transaction and grant at most once.
    if v_transaction_id is not null then
        update public.coin_wallets
           set balance = balance + v_amount,
               updated_at = now()
         where user_id = new.id;
    end if;

    return new;
end;
$$;

revoke all on function public.grant_new_user_welcome_bonus() from public, anon, authenticated;

drop trigger if exists trg_coin_welcome_bonus_after_auth_user_insert on auth.users;
create trigger trg_coin_welcome_bonus_after_auth_user_insert
after insert on auth.users
for each row execute function public.grant_new_user_welcome_bonus();
