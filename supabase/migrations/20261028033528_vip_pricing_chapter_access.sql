-- Keep VIP status authoritative in PostgreSQL. Expired memberships never unlock content.
create or replace function public.current_user_has_active_vip()
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select auth.uid() is not null
       and exists (
            select 1
              from public.vip_memberships membership
             where membership.user_id = auth.uid()
               and (membership.expires_at is null or membership.expires_at > now())
       );
$$;
revoke all on function public.current_user_has_active_vip() from public, anon;
grant execute on function public.current_user_has_active_vip() to anon, authenticated;

-- Prices are enforced server-side; the client never supplies a price.
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
    v_price := case p_plan when 'month' then 1000 when 'six_months' then 5000 when 'lifetime' then 10000 else null end;
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
revoke all on function public.purchase_vip(text, uuid) from public, anon;
grant execute on function public.purchase_vip(text, uuid) to authenticated;

-- VIP can read every published community chapter without creating permanent chapter unlocks.
drop policy if exists community_chapters_public_read on public.community_novel_chapters;
drop policy if exists community_chapters_access_read on public.community_novel_chapters;
create policy community_chapters_access_read on public.community_novel_chapters
    for select using (
        exists (
            select 1 from public.community_novels n
             where n.id = community_novel_chapters.novel_id
               and (
                    n.author_id = auth.uid()
                    or (
                        n.status = 'published'
                        and community_novel_chapters.status = 'published'
                        and (
                            public.current_user_has_active_vip()
                            or exists (
                                select 1 from public.chapter_unlocks u
                                 where u.user_id = auth.uid()
                                   and u.chapter_key = 'community:' || community_novel_chapters.novel_id::text || ':' || community_novel_chapters.id::text
                            )
                        )
                    )
               )
        )
    );

create or replace function public.read_community_chapter(p_novel_id uuid, p_chapter_id uuid)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_content text;
begin
    if v_user is null then
        raise exception 'authentication required' using errcode = '42501';
    end if;
    select c.content into v_content
      from public.community_novel_chapters c
      join public.community_novels n on n.id = c.novel_id
     where c.id = p_chapter_id
       and c.novel_id = p_novel_id
       and c.status = 'published'
       and n.status = 'published'
       and (
            n.author_id = v_user
            or public.current_user_has_active_vip()
            or exists (
                select 1 from public.chapter_unlocks u
                 where u.user_id = v_user
                   and u.chapter_key = 'community:' || c.novel_id::text || ':' || c.id::text
            )
       );
    if v_content is null then
        raise exception 'chapter is locked or unavailable' using errcode = '42501';
    end if;
    return v_content;
end;
$$;
revoke all on function public.read_community_chapter(uuid, uuid) from public, anon;
grant execute on function public.read_community_chapter(uuid, uuid) to authenticated;

-- Validate every supplied key before applying the VIP bypass; VIP responses do not persist free unlocks.
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
    select balance into v_balance
      from public.coin_wallets where user_id = v_user for update;

    if public.current_user_has_active_vip() then
        return jsonb_build_object(
            'allowed', true,
            'status', 'vip',
            'balance', v_balance,
            'required', 0,
            'charged', 0,
            'unlocked', '[]'::jsonb,
            'already_unlocked', '[]'::jsonb
        );
    end if;

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

-- VIP subscribers may view and write chapter comments on published chapters they can read.
drop policy if exists community_comments_read on public.community_comments;
create policy community_comments_read on public.community_comments
    for select to authenticated using (
        status = 'visible'
        and exists (
            select 1 from public.community_novels n
             where n.id = community_comments.novel_id
               and (n.status = 'published' or n.author_id = auth.uid())
        )
        and (
            community_comments.chapter_id is null
            or exists (
                select 1 from public.community_novel_chapters c
                join public.community_novels n on n.id = c.novel_id
                where c.id = community_comments.chapter_id
                  and c.novel_id = community_comments.novel_id
                  and (
                      n.author_id = auth.uid()
                      or public.current_user_has_active_vip()
                      or exists (
                          select 1 from public.chapter_unlocks u
                           where u.user_id = auth.uid()
                             and u.chapter_key = 'community:' || c.novel_id::text || ':' || c.id::text
                      )
                  )
            )
        )
    );

drop policy if exists community_comments_insert_own on public.community_comments;
create policy community_comments_insert_own on public.community_comments
    for insert to authenticated with check (
        user_id = auth.uid()
        and status = 'visible'
        and parent_id is null
        and exists (
            select 1 from public.community_novels n
             where n.id = community_comments.novel_id
               and (n.status = 'published' or n.author_id = auth.uid())
        )
        and (
            community_comments.chapter_id is null
            or exists (
                select 1 from public.community_novel_chapters c
                join public.community_novels n on n.id = c.novel_id
                where c.id = community_comments.chapter_id
                  and c.novel_id = community_comments.novel_id
                  and c.status = 'published'
                  and (
                      n.author_id = auth.uid()
                      or public.current_user_has_active_vip()
                      or exists (
                          select 1 from public.chapter_unlocks u
                           where u.user_id = auth.uid()
                             and u.chapter_key = 'community:' || c.novel_id::text || ':' || c.id::text
                      )
                  )
            )
        )
    );

create or replace function public.add_community_comment(
    p_novel_id uuid,
    p_chapter_id uuid,
    p_content text,
    p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_comment_id uuid;
    v_body text := trim(coalesce(p_content, ''));
begin
    if v_user is null then
        raise exception 'authentication required' using errcode = '42501';
    end if;
    if p_request_id is null or char_length(v_body) < 1 or char_length(v_body) > 1000 then
        raise exception 'comment must contain 1 to 1000 characters and a request id';
    end if;
    if (select count(*) from public.community_comments c
         where c.user_id = v_user and c.created_at > now() - interval '1 minute') >= 10 then
        raise exception 'comment rate limit exceeded';
    end if;
    if not exists (
        select 1 from public.community_novels n
         where n.id = p_novel_id and (n.status = 'published' or n.author_id = v_user)
    ) then
        raise exception 'novel is unavailable';
    end if;
    if p_chapter_id is not null and not exists (
        select 1 from public.community_novel_chapters c
        join public.community_novels n on n.id = c.novel_id
        where c.id = p_chapter_id and c.novel_id = p_novel_id
          and c.status = 'published'
          and (n.author_id = v_user or public.current_user_has_active_vip() or exists (
              select 1 from public.chapter_unlocks u
               where u.user_id = v_user
                 and u.chapter_key = 'community:' || c.novel_id::text || ':' || c.id::text
          ))
    ) then
        raise exception 'chapter is locked or unavailable';
    end if;

    insert into public.community_comments(user_id, novel_id, chapter_id, content, status, request_id)
    values (v_user, p_novel_id, p_chapter_id, v_body, 'visible', p_request_id)
    on conflict (user_id, request_id) where request_id is not null do nothing
    returning id into v_comment_id;
    if v_comment_id is null then
        select c.id into v_comment_id from public.community_comments c
         where c.user_id = v_user and c.request_id = p_request_id;
    end if;
    return jsonb_build_object('id', v_comment_id, 'request_id', p_request_id);
end;
$$;
revoke all on function public.add_community_comment(uuid, uuid, text, uuid) from public, anon;
grant execute on function public.add_community_comment(uuid, uuid, text, uuid) to authenticated;
