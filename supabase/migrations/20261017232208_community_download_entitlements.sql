-- Preserve community chapters already paid for and downloaded before the central lock system.
insert into public.chapter_unlocks(user_id, chapter_key, price_coins, unlocked_at)
select distinct r.user_id,
       'community:' || c.novel_id::text || ':' || c.id::text,
       1,
       coalesce(r.updated_at, r.created_at)
  from public.coin_download_reservations r
  join public.community_novel_chapters c
    on r.chapter_key = 'community-novel:' || c.novel_id::text || ':' || c.id::text
 where r.status = 'committed'
   and not exists (
       select 1 from public.chapter_unlocks u
        where u.user_id = r.user_id
          and u.chapter_key = 'community:' || c.novel_id::text || ':' || c.id::text
   )
on conflict (user_id, chapter_key) do nothing;

-- Authors receive a free server-side entitlement to their own published community chapters.
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
    v_free_keys text[];
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
           or (
                input.chapter_key !~ '^(manga|novel):[0-9a-f]{64}$'
                and input.chapter_key !~ '^community:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           )
    ) then
        raise exception 'invalid chapter key';
    end if;
    if exists (
        select 1 from unnest(p_chapter_keys) as input(chapter_key)
        where input.chapter_key like 'community:%'
          and not exists (
              select 1
                from public.community_novel_chapters c
                join public.community_novels n on n.id = c.novel_id
               where input.chapter_key = 'community:' || c.novel_id::text || ':' || c.id::text
                 and c.status = 'published'
                 and n.status = 'published'
          )
    ) then
        raise exception 'community chapter is not published or does not exist';
    end if;

    insert into public.coin_wallets(user_id) values (v_user) on conflict (user_id) do nothing;
    select balance into v_balance from public.coin_wallets where user_id = v_user for update;

    select coalesce(array_agg(candidate.chapter_key order by candidate.chapter_key), '{}'::text[])
      into v_already_keys
      from (
          select distinct input.chapter_key
            from unnest(p_chapter_keys) as input(chapter_key)
           where exists (
               select 1 from public.chapter_unlocks u
                where u.user_id = v_user and u.chapter_key = input.chapter_key
           )
      ) candidate;

    select coalesce(array_agg(candidate.chapter_key order by candidate.chapter_key), '{}'::text[])
      into v_free_keys
      from (
          select distinct input.chapter_key
            from unnest(p_chapter_keys) as input(chapter_key)
            join public.community_novel_chapters c
              on input.chapter_key = 'community:' || c.novel_id::text || ':' || c.id::text
            join public.community_novels n on n.id = c.novel_id
           where n.author_id = v_user
             and not exists (
                 select 1 from public.chapter_unlocks u
                  where u.user_id = v_user and u.chapter_key = input.chapter_key
             )
      ) candidate;

    select coalesce(array_agg(candidate.chapter_key order by candidate.chapter_key), '{}'::text[])
      into v_new_keys
      from (
          select distinct input.chapter_key
            from unnest(p_chapter_keys) as input(chapter_key)
           where not exists (
               select 1 from public.chapter_unlocks u
                where u.user_id = v_user and u.chapter_key = input.chapter_key
           )
             and input.chapter_key <> all(v_free_keys)
      ) candidate;

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
        update public.coin_wallets set balance = balance - v_cost, updated_at = now() where user_id = v_user;
    end if;
    insert into public.chapter_unlocks(user_id, chapter_key, price_coins)
    select v_user, key_value, 1
      from unnest(v_new_keys || v_free_keys) as keys(key_value);
    if v_cost > 0 then
        insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
        select v_user, -1, 'chapter_unlock', 'chapter_unlock:' || key_value, key_value
          from unnest(v_new_keys) as keys(key_value);
    end if;

    select balance into v_balance from public.coin_wallets where user_id = v_user;
    return jsonb_build_object(
        'allowed', true,
        'status', 'unlocked',
        'balance', v_balance,
        'required', v_cost,
        'charged', v_cost,
        'unlocked', to_jsonb(v_new_keys || v_free_keys),
        'already_unlocked', to_jsonb(v_already_keys)
    );
end;
$$;
revoke all on function public.unlock_chapters(text[]) from public, anon;
grant execute on function public.unlock_chapters(text[]) to authenticated;
