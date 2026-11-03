-- Chapter access is granted only after this server-side entitlement is recorded.
-- Chapter content itself comes from external/community catalogues, so this validates
-- stable key formats and makes payment/entitlement authoritative, not source existence.

create table if not exists public.chapter_unlocks (
    user_id uuid not null references auth.users(id) on delete cascade,
    chapter_key text not null,
    price_coins integer not null default 1 check (price_coins = 1),
    unlocked_at timestamptz not null default now(),
    primary key (user_id, chapter_key),
    check (
        chapter_key ~ '^(manga|novel):[0-9a-f]{64}$'
        or chapter_key ~ '^community:[0-9a-f-]{36}:[0-9a-f-]{36}$'
    )
);
create index if not exists chapter_unlocks_user_unlocked_idx
    on public.chapter_unlocks(user_id, unlocked_at desc);

alter table public.chapter_unlocks enable row level security;
revoke all on public.chapter_unlocks from anon, authenticated;
grant select on public.chapter_unlocks to authenticated;
drop policy if exists chapter_unlocks_select_own on public.chapter_unlocks;
create policy chapter_unlocks_select_own on public.chapter_unlocks
    for select to authenticated using (user_id = auth.uid());

-- Preserve every transaction kind already used by the wallet, adding chapter_unlock.
alter table public.coin_transactions
    drop constraint if exists coin_transactions_kind_check;
alter table public.coin_transactions
    add constraint coin_transactions_kind_check
    check (kind in (
        'ad_reward', 'play_purchase', 'download', 'download_refund', 'vip_purchase',
        'daily_login_reward', 'welcome_bonus', 'shop_purchase', 'admin_grant',
        'reading_task_reward', 'chapter_unlock'
    ));

create or replace function public.unlock_chapters(p_chapter_keys text[])
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_balance integer;
    v_new_keys text[];
    v_already_keys text[];
    v_cost integer;
begin
    if v_user is null then
        raise exception 'authentication required' using errcode = '42501';
    end if;
    if p_chapter_keys is null or cardinality(p_chapter_keys) < 1 or cardinality(p_chapter_keys) > 100 then
        raise exception 'chapter selection must contain 1 to 100 entries';
    end if;
    if exists (
        select 1 from unnest(p_chapter_keys) as input(chapter_key)
        where input.chapter_key is null
           or input.chapter_key !~ '^(manga|novel):[0-9a-f]{64}$'
              and input.chapter_key !~ '^community:[0-9a-f-]{36}:[0-9a-f-]{36}$'
    ) then
        raise exception 'invalid chapter key';
    end if;

    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    select balance into v_balance
      from public.coin_wallets where user_id = v_user for update;

    select coalesce(array_agg(candidate.chapter_key order by candidate.chapter_key), '{}'::text[])
      into v_new_keys
      from (
          select distinct input.chapter_key
            from unnest(p_chapter_keys) as input(chapter_key)
           where not exists (
               select 1 from public.chapter_unlocks unlocked
                where unlocked.user_id = v_user and unlocked.chapter_key = input.chapter_key
           )
      ) as candidate;

    select coalesce(array_agg(candidate.chapter_key order by candidate.chapter_key), '{}'::text[])
      into v_already_keys
      from (
          select distinct input.chapter_key
            from unnest(p_chapter_keys) as input(chapter_key)
           where exists (
               select 1 from public.chapter_unlocks unlocked
                where unlocked.user_id = v_user and unlocked.chapter_key = input.chapter_key
           )
      ) as candidate;

    v_cost := coalesce(array_length(v_new_keys, 1), 0);
    if v_balance < v_cost then
        return jsonb_build_object(
            'allowed', false,
            'status', 'insufficient',
            'balance', v_balance,
            'required', v_cost,
            'unlocked', to_jsonb(v_already_keys),
            'charged', 0
        );
    end if;

    if v_cost > 0 then
        update public.coin_wallets
           set balance = balance - v_cost, updated_at = now()
         where user_id = v_user;

        insert into public.chapter_unlocks(user_id, chapter_key, price_coins)
        select v_user, keys.chapter_key, 1
          from unnest(v_new_keys) as keys(chapter_key);

        insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
        select v_user, -1, 'chapter_unlock', 'chapter_unlock:' || keys.chapter_key, keys.chapter_key
          from unnest(v_new_keys) as keys(chapter_key);
    end if;

    select balance into v_balance from public.coin_wallets where user_id = v_user;
    return jsonb_build_object(
        'allowed', true,
        'status', 'unlocked',
        'balance', v_balance,
        'required', v_cost,
        'charged', v_cost,
        'unlocked', to_jsonb(v_new_keys),
        'already_unlocked', to_jsonb(v_already_keys)
    );
end;
$$;

revoke all on function public.unlock_chapters(text[]) from public, anon;
grant execute on function public.unlock_chapters(text[]) to authenticated;
