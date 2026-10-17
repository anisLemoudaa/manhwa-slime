-- Extensible in-app shop and profile-frame inventory.
-- Coin balances are changed only inside authenticated, idempotent RPCs.

create table if not exists public.shop_items (
    id text primary key check (length(id) between 1 and 80),
    item_type text not null check (item_type in ('profile_frame', 'xp', 'potion', 'other')),
    name text not null check (length(name) between 1 and 100),
    description text not null default '',
    price_coins integer not null default 0 check (price_coins >= 0),
    payload jsonb not null default '{}'::jsonb,
    rarity text check (rarity is null or rarity in ('common', 'popular', 'rare', 'epic', 'legendary', 'exclusive', 'new')),
    badge_label text,
    active boolean not null default true,
    featured boolean not null default false,
    sort_order integer not null default 0,
    start_at timestamptz,
    end_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (start_at is null or end_at is null or end_at > start_at)
);

create index if not exists shop_items_active_order_idx
    on public.shop_items(active, sort_order, id);

create table if not exists public.user_inventory (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    item_id text not null references public.shop_items(id),
    quantity integer not null default 1 check (quantity >= 0),
    is_equipped boolean not null default false,
    acquired_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    expires_at timestamptz,
    metadata jsonb not null default '{}'::jsonb,
    unique (user_id, item_id)
);

create unique index if not exists user_inventory_one_equipped_item_idx
    on public.user_inventory(user_id)
    where is_equipped;

create table if not exists public.shop_purchases (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    item_id text not null references public.shop_items(id),
    request_id uuid not null,
    quantity integer not null default 1 check (quantity > 0),
    price_coins integer not null check (price_coins >= 0),
    created_at timestamptz not null default now(),
    unique (user_id, request_id)
);

alter table public.coin_transactions
    drop constraint if exists coin_transactions_kind_check;
alter table public.coin_transactions
    add constraint coin_transactions_kind_check
    check (kind in ('ad_reward', 'play_purchase', 'download', 'download_refund', 'vip_purchase', 'daily_login_reward', 'welcome_bonus', 'shop_purchase'));

alter table public.shop_items enable row level security;
alter table public.user_inventory enable row level security;
alter table public.shop_purchases enable row level security;

drop policy if exists shop_items_read_active on public.shop_items;
create policy shop_items_read_active on public.shop_items
    for select to anon, authenticated using (active and (start_at is null or start_at <= now()) and (end_at is null or end_at > now()));
drop policy if exists user_inventory_select_own on public.user_inventory;
create policy user_inventory_select_own on public.user_inventory
    for select to authenticated using (user_id = auth.uid());
drop policy if exists shop_purchases_select_own on public.shop_purchases;
create policy shop_purchases_select_own on public.shop_purchases
    for select to authenticated using (user_id = auth.uid());

revoke all on public.shop_items, public.user_inventory, public.shop_purchases from anon, authenticated;
grant select on public.shop_items to anon, authenticated;
grant select on public.user_inventory, public.shop_purchases to authenticated;
grant select on public.coin_wallets to authenticated;

-- User profile frame state cannot be written through REST; only equip_profile_frame may set it.
alter table public.user_profiles add column if not exists equipped_frame_id text;
revoke update on public.user_profiles from authenticated;
grant update (display_name, avatar_data, cover_data, level, rank, updated_at)
    on public.user_profiles to authenticated;

-- Initial catalog maps local transparent WebP resources to stable server item IDs.
insert into public.shop_items (id, item_type, name, description, price_coins, payload, rarity, badge_label, active, featured, sort_order)
values
 ('frame_fire', 'profile_frame', 'إطار النار', 'إطار متوهج بلهيب متقد حول صورتك الشخصية.', 100, '{"frame_id":"frame_fire","asset_ref":"frame_fire"}', 'popular', 'شائع', true, true, 10),
 ('frame_ice', 'profile_frame', 'إطار الجليد', 'إطار بلوري أزرق يحيط بصورتك الشخصية.', 100, '{"frame_id":"frame_ice","asset_ref":"frame_ice"}', 'common', null, true, false, 20),
 ('frame_dark', 'profile_frame', 'إطار الطاقة المظلمة', 'هالة بنفسجية غامضة حول صورتك.', 150, '{"frame_id":"frame_dark","asset_ref":"frame_dark"}', 'rare', 'نادر', true, false, 30),
 ('frame_king', 'profile_frame', 'إطار الملك', 'إطار ذهبي ملكي بتفاصيل متألقة.', 300, '{"frame_id":"frame_king","asset_ref":"frame_king"}', 'exclusive', 'حصري', true, false, 40),
 ('frame_galaxy', 'profile_frame', 'إطار المجرة', 'مدار كوني متوهج حول صورتك.', 250, '{"frame_id":"frame_galaxy","asset_ref":"frame_galaxy"}', 'legendary', 'أسطوري', true, false, 50),
 ('frame_samurai', 'profile_frame', 'إطار الساموراي', 'إطار مستوحى من ألوان الساموراي والزهور.', 200, '{"frame_id":"frame_samurai","asset_ref":"frame_samurai"}', 'new', 'جديد', true, false, 60)
on conflict (id) do nothing;

create or replace function public.list_shop_catalog()
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select coalesce(jsonb_agg(to_jsonb(s) order by s.sort_order, s.id), '[]'::jsonb)
    from (
        select id, item_type, name, description, price_coins, payload, rarity,
               badge_label, active, featured, sort_order, start_at, end_at
        from public.shop_items
        where active and (start_at is null or start_at <= now()) and (end_at is null or end_at > now())
    ) s;
$$;

create or replace function public.purchase_shop_item(p_item_id text, p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_item public.shop_items%rowtype;
    v_existing public.shop_purchases%rowtype;
    v_balance integer;
    v_new_balance integer;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if p_request_id is null or length(trim(coalesce(p_item_id, ''))) = 0 then raise exception 'invalid request'; end if;

    select * into v_existing from public.shop_purchases
      where user_id = v_user and request_id = p_request_id;
    if found then
        if v_existing.item_id <> p_item_id then
            return jsonb_build_object('ok', false, 'reason', 'request_conflict');
        end if;
        select balance into v_balance from public.coin_wallets where user_id = v_user;
        return jsonb_build_object('ok', true, 'already_processed', true, 'balance', coalesce(v_balance, 0), 'item_id', v_existing.item_id);
    end if;

    select * into v_item from public.shop_items
      where id = p_item_id and active and (start_at is null or start_at <= now()) and (end_at is null or end_at > now())
      for share;
    if not found then return jsonb_build_object('ok', false, 'reason', 'unavailable'); end if;

    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    select balance into v_balance from public.coin_wallets where user_id = v_user for update;

    -- Recheck after taking the wallet lock so retries and concurrent purchases serialize per user.
    select * into v_existing from public.shop_purchases
      where user_id = v_user and request_id = p_request_id;
    if found then
        if v_existing.item_id <> p_item_id then
            return jsonb_build_object('ok', false, 'reason', 'request_conflict');
        end if;
        return jsonb_build_object('ok', true, 'already_processed', true, 'balance', v_balance, 'item_id', v_existing.item_id);
    end if;

    if v_item.item_type = 'profile_frame' and exists (
        select 1 from public.user_inventory where user_id = v_user and item_id = p_item_id and quantity > 0
    ) then return jsonb_build_object('ok', false, 'reason', 'owned'); end if;
    if v_balance < v_item.price_coins then
        return jsonb_build_object('ok', false, 'reason', 'insufficient_funds', 'balance', v_balance);
    end if;

    update public.coin_wallets set balance = balance - v_item.price_coins, updated_at = now() where user_id = v_user;
    insert into public.user_inventory(user_id, item_id, quantity, metadata)
    values (v_user, p_item_id, 1, jsonb_build_object('item_type', v_item.item_type))
    on conflict (user_id, item_id) do update set quantity = public.user_inventory.quantity + 1, updated_at = now();
    insert into public.shop_purchases(user_id, item_id, request_id, price_coins)
    values (v_user, p_item_id, p_request_id, v_item.price_coins);
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
    values (v_user, -v_item.price_coins, 'shop_purchase', 'shop:' || p_request_id::text, p_item_id);
    select balance into v_new_balance from public.coin_wallets where user_id = v_user;
    return jsonb_build_object('ok', true, 'already_processed', false, 'balance', v_new_balance, 'item_id', p_item_id, 'quantity', 1);
end;
$$;

create or replace function public.equip_profile_frame(p_item_id text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if p_item_id is not null and p_item_id <> '' then
        if not exists (
            select 1 from public.shop_items s join public.user_inventory i on i.item_id = s.id
            where s.id = p_item_id and s.item_type = 'profile_frame' and i.user_id = v_user and i.quantity > 0
        ) then raise exception 'frame not owned'; end if;
        update public.user_inventory set is_equipped = false, updated_at = now()
          where user_id = v_user and is_equipped;
        update public.user_inventory set is_equipped = true, updated_at = now()
          where user_id = v_user and item_id = p_item_id;
    else
        update public.user_inventory set is_equipped = false, updated_at = now()
          where user_id = v_user and is_equipped;
    end if;
    insert into public.user_profiles(user_id, display_name)
    values (v_user, 'قارئ السلايم') on conflict (user_id) do nothing;
    update public.user_profiles set equipped_frame_id = nullif(p_item_id, ''), updated_at = now()
      where user_id = v_user;
    return jsonb_build_object('ok', true, 'equipped_frame_id', nullif(p_item_id, ''));
end;
$$;

revoke all on function public.list_shop_catalog() from public;
revoke all on function public.purchase_shop_item(text, uuid) from public, anon;
revoke all on function public.equip_profile_frame(text) from public, anon;
grant execute on function public.list_shop_catalog() to anon, authenticated;
grant execute on function public.purchase_shop_item(text, uuid) to authenticated;
grant execute on function public.equip_profile_frame(text) to authenticated;
