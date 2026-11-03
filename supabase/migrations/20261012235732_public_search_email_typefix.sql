-- The auth.users.email column is varchar; cast it to the declared RPC text result.
create or replace function public.search_public_users(p_query text, p_limit integer default 20, p_offset integer default 0)
returns table (
    user_id uuid, display_name text, avatar_data text, level integer, rank text,
    follower_count bigint, email text, is_following boolean
)
language plpgsql stable security definer set search_path = public, pg_temp
as $$
declare v_query text := lower(trim(coalesce(p_query, '')));
begin
    if length(v_query) < 2 then raise exception 'query must contain at least 2 characters'; end if;
    if length(v_query) > 100 then raise exception 'query too long'; end if;
    return query
    with candidates as (
        select u.id, coalesce(p.display_name, c.author_name, 'قارئ السلايم') as name,
            p.avatar_data, coalesce(p.level, c.level, 1) as account_level,
            coalesce(p.rank, c.rank, 'F') as account_rank, p.follower_count_override,
            p.email_visibility, coalesce(p.profile_visibility, 'public') as profile_visibility,
            coalesce(p.followers_visibility, 'public') as followers_visibility, u.email as account_email,
            case when lower(coalesce(p.display_name, c.author_name, '')) = v_query then 0
                when position(v_query in lower(coalesce(p.display_name, c.author_name, ''))) = 1 then 1
                when position(v_query in lower(coalesce(p.display_name, c.author_name, ''))) > 0 then 2
                when p.email_visibility = 'public' and lower(coalesce(u.email, '')) = v_query then 3
                when p.email_visibility = 'public' and position(v_query in lower(coalesce(u.email, ''))) = 1 then 4
                else 5 end as match_order
        from auth.users u
        left join public.user_profiles p on p.user_id = u.id
        left join lateral (
            select cm.author_name, cm.author_avatar, cm.level, cm.rank from public.comments cm
            where cm.user_id = u.id and cm.hidden = false and cm.parent_id is null
            order by cm.created_at desc, cm.id desc limit 1
        ) c on true where u.id is not null
    )
    select c.id, c.name, c.avatar_data, c.account_level, c.account_rank,
        case when c.followers_visibility = 'public' or c.id = auth.uid() or
            (c.followers_visibility = 'followers' and exists (select 1 from public.user_follows f where f.follower_id=auth.uid() and f.followed_id=c.id))
            then coalesce(c.follower_count_override::bigint, (select count(*) from public.user_follows f where f.followed_id=c.id)) else null end,
        case when c.email_visibility = 'public' then c.account_email::text else null end,
        coalesce((select exists(select 1 from public.user_follows f where f.follower_id=auth.uid() and f.followed_id=c.id)), false)
    from candidates c
    where (c.profile_visibility='public' or c.id=auth.uid() or
        (c.profile_visibility='followers' and exists(select 1 from public.user_follows f where f.follower_id=auth.uid() and f.followed_id=c.id)))
      and (position(v_query in lower(c.name)) > 0 or
        (c.email_visibility='public' and position(v_query in lower(coalesce(c.account_email,''))) > 0))
    order by c.match_order, lower(c.name), c.id
    limit least(greatest(coalesce(p_limit,20),1),20) offset greatest(coalesce(p_offset,0),0);
end;
$$;
revoke all on function public.search_public_users(text,integer,integer) from public;
grant execute on function public.search_public_users(text,integer,integer) to anon, authenticated;
