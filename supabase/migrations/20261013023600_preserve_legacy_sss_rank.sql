-- Preserve the existing creator-only SSS+ rank until its XP threshold is defined.
-- Existing SSS+ profiles still gain RP; their rank is not silently downgraded.
alter table public.rank_xp_uses
    drop constraint if exists rank_xp_uses_rank_before_check;
alter table public.rank_xp_uses
    add constraint rank_xp_uses_rank_before_check
    check (rank_before in ('F','E','D','C','B','A','S','SS','SS+','SSS+'));
alter table public.rank_xp_uses
    drop constraint if exists rank_xp_uses_rank_after_check;
alter table public.rank_xp_uses
    add constraint rank_xp_uses_rank_after_check
    check (rank_after in ('F','E','D','C','B','A','S','SS','SS+','SSS+'));

create or replace function public.award_rank_xp_for_chapter(p_chapter_key text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_profile public.user_profiles%rowtype;
    v_inserted integer;
    v_after integer;
    v_new_rank text;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if p_chapter_key is null or length(trim(p_chapter_key)) not between 1 and 240 then
        raise exception 'invalid chapter key';
    end if;

    insert into public.user_profiles(user_id, display_name)
    values (v_user, 'قارئ السلايم') on conflict (user_id) do nothing;
    select * into v_profile from public.user_profiles where user_id = v_user for update;

    if exists (
        select 1 from public.rank_xp_chapter_rewards
        where user_id = v_user and created_at > now() - interval '15 seconds'
    ) then
        return jsonb_build_object('ok', false, 'reason', 'rate_limited',
            'rank_xp', v_profile.rank_xp, 'rank', v_profile.rank);
    end if;

    insert into public.rank_xp_chapter_rewards(user_id, chapter_key)
    values (v_user, trim(p_chapter_key)) on conflict (user_id, chapter_key) do nothing;
    get diagnostics v_inserted = row_count;
    if v_inserted = 0 then
        return jsonb_build_object('ok', true, 'already_rewarded', true,
            'rank_xp', v_profile.rank_xp, 'rank', v_profile.rank);
    end if;

    v_after := v_profile.rank_xp + 1;
    v_new_rank := case
        when v_profile.rank = 'SSS+' then 'SSS+'
        when v_after >= 5500 then 'SS+'
        when v_after >= 3500 then 'SS'
        when v_after >= 2000 then 'S'
        when v_after >= 1200 then 'A'
        when v_after >= 700 then 'B'
        when v_after >= 350 then 'C'
        when v_after >= 150 then 'D'
        when v_after >= 50 then 'E'
        else 'F'
    end;
    update public.user_profiles
      set rank_xp = v_after, rank = v_new_rank, updated_at = now()
      where user_id = v_user;

    return jsonb_build_object('ok', true, 'already_rewarded', false,
        'rank_xp', v_after, 'rank', v_new_rank, 'points_added', 1);
end;
$$;

create or replace function public.use_inventory_item(p_inventory_id uuid, p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_inventory public.user_inventory%rowtype;
    v_item public.shop_items%rowtype;
    v_existing public.rank_xp_uses%rowtype;
    v_profile public.user_profiles%rowtype;
    v_points integer;
    v_before integer;
    v_after integer;
    v_old_rank text;
    v_new_rank text;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if p_inventory_id is null or p_request_id is null then raise exception 'invalid request'; end if;

    select * into v_existing from public.rank_xp_uses
      where user_id = v_user and request_id = p_request_id;
    if found then
        return jsonb_build_object('ok', true, 'already_processed', true,
            'rank_xp', v_existing.rank_xp_after, 'rank', v_existing.rank_after,
            'points_added', v_existing.points_added);
    end if;

    select * into v_inventory from public.user_inventory
      where id = p_inventory_id and user_id = v_user for update;
    if not found or v_inventory.quantity < 1 then
        return jsonb_build_object('ok', false, 'reason', 'not_owned');
    end if;

    select * into v_item from public.shop_items
      where id = v_inventory.item_id and item_type = 'potion' and active
        and (start_at is null or start_at <= now()) and (end_at is null or end_at > now())
      for share;
    if not found then return jsonb_build_object('ok', false, 'reason', 'unavailable'); end if;
    if coalesce(v_item.payload->>'effect_type', '') <> 'rank_xp' then
        return jsonb_build_object('ok', false, 'reason', 'unsupported_effect');
    end if;

    v_points := (v_item.payload->>'effect_value')::integer;
    if v_points < 1 or v_points > 40 then
        raise exception 'invalid rank XP potion configuration';
    end if;

    insert into public.user_profiles(user_id, display_name)
    values (v_user, 'قارئ السلايم') on conflict (user_id) do nothing;
    select * into v_profile from public.user_profiles where user_id = v_user for update;
    v_before := v_profile.rank_xp;
    v_old_rank := coalesce(v_profile.rank, 'F');
    v_after := v_before + v_points;
    v_new_rank := case
        when v_old_rank = 'SSS+' then 'SSS+'
        when v_after >= 5500 then 'SS+'
        when v_after >= 3500 then 'SS'
        when v_after >= 2000 then 'S'
        when v_after >= 1200 then 'A'
        when v_after >= 700 then 'B'
        when v_after >= 350 then 'C'
        when v_after >= 150 then 'D'
        when v_after >= 50 then 'E'
        else 'F'
    end;

    update public.user_inventory
      set quantity = quantity - 1, updated_at = now()
      where id = v_inventory.id and user_id = v_user and quantity > 0;
    if not found then return jsonb_build_object('ok', false, 'reason', 'not_owned'); end if;

    update public.user_profiles
      set rank_xp = v_after, rank = v_new_rank, updated_at = now()
      where user_id = v_user;

    insert into public.rank_xp_uses
      (user_id, inventory_id, item_id, request_id, points_added,
       rank_xp_before, rank_xp_after, rank_before, rank_after)
    values
      (v_user, v_inventory.id, v_item.id, p_request_id, v_points,
       v_before, v_after, v_old_rank, v_new_rank);

    return jsonb_build_object('ok', true, 'already_processed', false,
        'rank_xp', v_after, 'rank', v_new_rank, 'points_added', v_points,
        'rank_changed', v_old_rank <> v_new_rank, 'quantity', v_inventory.quantity - 1);
end;
$$;

revoke all on function public.award_rank_xp_for_chapter(text) from public, anon;
grant execute on function public.award_rank_xp_for_chapter(text) to authenticated;
revoke all on function public.use_inventory_item(uuid, uuid) from public, anon;
grant execute on function public.use_inventory_item(uuid, uuid) to authenticated;
