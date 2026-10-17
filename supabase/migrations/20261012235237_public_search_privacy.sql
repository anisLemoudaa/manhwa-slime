-- Public discovery and per-field privacy. Values are server-filtered in RPCs;
-- clients cannot read auth.users or write privacy columns directly.

alter table public.user_profiles
    add column if not exists profile_visibility text not null default 'public',
    add column if not exists cover_visibility text not null default 'public',
    add column if not exists favorites_visibility text not null default 'private',
    add column if not exists novel_favorites_visibility text not null default 'private',
    add column if not exists sources_visibility text not null default 'private',
    add column if not exists reading_stats_visibility text not null default 'private',
    add column if not exists followers_visibility text not null default 'public',
    add column if not exists following_visibility text not null default 'public',
    add column if not exists comments_visibility text not null default 'public',
    add column if not exists email_visibility text not null default 'private',
    add column if not exists joined_at_visibility text not null default 'public',
    add column if not exists equipped_frame_visibility text not null default 'public';

alter table public.user_profiles
    add constraint user_profiles_privacy_values_check check (
        profile_visibility in ('public','followers','private') and
        cover_visibility in ('public','followers','private') and
        favorites_visibility in ('public','followers','private') and
        novel_favorites_visibility in ('public','followers','private') and
        sources_visibility in ('public','followers','private') and
        reading_stats_visibility in ('public','followers','private') and
        followers_visibility in ('public','followers','private') and
        following_visibility in ('public','followers','private') and
        comments_visibility in ('public','followers','private') and
        email_visibility in ('public','followers','private') and
        joined_at_visibility in ('public','followers','private') and
        equipped_frame_visibility in ('public','followers','private')
    );

create or replace function public.update_my_privacy_settings(p_settings jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_key text;
    v_value text;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
        raise exception 'Google sign-in required';
    end if;
    if jsonb_typeof(p_settings) <> 'object' or p_settings = '{}'::jsonb then
        raise exception 'settings required';
    end if;
    for v_key, v_value in select key, value #>> '{}' from jsonb_each(p_settings) loop
        if v_value is null or v_key not in (
            'profile_visibility','cover_visibility','favorites_visibility','novel_favorites_visibility',
            'sources_visibility','reading_stats_visibility','followers_visibility','following_visibility',
            'comments_visibility','email_visibility','joined_at_visibility','equipped_frame_visibility'
        ) or v_value not in ('public','followers','private') then
            raise exception 'invalid privacy setting';
        end if;
    end loop;
    insert into public.user_profiles(user_id, display_name)
    values (v_user, 'قارئ السلايم') on conflict (user_id) do nothing;
    update public.user_profiles p set
        profile_visibility = coalesce(p_settings->>'profile_visibility', p.profile_visibility),
        cover_visibility = coalesce(p_settings->>'cover_visibility', p.cover_visibility),
        favorites_visibility = coalesce(p_settings->>'favorites_visibility', p.favorites_visibility),
        novel_favorites_visibility = coalesce(p_settings->>'novel_favorites_visibility', p.novel_favorites_visibility),
        sources_visibility = coalesce(p_settings->>'sources_visibility', p.sources_visibility),
        reading_stats_visibility = coalesce(p_settings->>'reading_stats_visibility', p.reading_stats_visibility),
        followers_visibility = coalesce(p_settings->>'followers_visibility', p.followers_visibility),
        following_visibility = coalesce(p_settings->>'following_visibility', p.following_visibility),
        comments_visibility = coalesce(p_settings->>'comments_visibility', p.comments_visibility),
        email_visibility = coalesce(p_settings->>'email_visibility', p.email_visibility),
        joined_at_visibility = coalesce(p_settings->>'joined_at_visibility', p.joined_at_visibility),
        equipped_frame_visibility = coalesce(p_settings->>'equipped_frame_visibility', p.equipped_frame_visibility),
        updated_at = now()
    where p.user_id = v_user;
    return jsonb_build_object('ok', true);
end;
$$;

create or replace function public.get_my_privacy_settings()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'profile_visibility', p.profile_visibility,
        'cover_visibility', p.cover_visibility,
        'favorites_visibility', p.favorites_visibility,
        'novel_favorites_visibility', p.novel_favorites_visibility,
        'sources_visibility', p.sources_visibility,
        'reading_stats_visibility', p.reading_stats_visibility,
        'followers_visibility', p.followers_visibility,
        'following_visibility', p.following_visibility,
        'comments_visibility', p.comments_visibility,
        'email_visibility', p.email_visibility,
        'joined_at_visibility', p.joined_at_visibility,
        'equipped_frame_visibility', p.equipped_frame_visibility
    )
    from public.user_profiles p where p.user_id = auth.uid();
$$;

create or replace function public.search_public_users(p_query text, p_limit integer default 20, p_offset integer default 0)
returns table (
    user_id uuid, display_name text, avatar_data text, level integer, rank text,
    follower_count bigint, email text, is_following boolean
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_query text := lower(trim(coalesce(p_query, '')));
begin
    if length(v_query) < 2 then raise exception 'query must contain at least 2 characters'; end if;
    if length(v_query) > 100 then raise exception 'query too long'; end if;
    return query
    with candidates as (
        select u.id,
            coalesce(p.display_name, c.author_name, 'قارئ السلايم') as name,
            p.avatar_data,
            coalesce(p.level, c.level, 1) as account_level,
            coalesce(p.rank, c.rank, 'F') as account_rank,
            p.follower_count_override,
            p.email_visibility,
            coalesce(p.profile_visibility, 'public') as profile_visibility,
            coalesce(p.followers_visibility, 'public') as followers_visibility,
            u.email as account_email,
            case
                when lower(coalesce(p.display_name, c.author_name, '')) = v_query then 0
                when position(v_query in lower(coalesce(p.display_name, c.author_name, ''))) = 1 then 1
                when position(v_query in lower(coalesce(p.display_name, c.author_name, ''))) > 0 then 2
                when p.email_visibility = 'public' and lower(coalesce(u.email, '')) = v_query then 3
                when p.email_visibility = 'public' and position(v_query in lower(coalesce(u.email, ''))) = 1 then 4
                else 5
            end as match_order
        from auth.users u
        left join public.user_profiles p on p.user_id = u.id
        left join lateral (
            select cm.author_name, cm.author_avatar, cm.level, cm.rank
            from public.comments cm
            where cm.user_id = u.id and cm.hidden = false and cm.parent_id is null
            order by cm.created_at desc, cm.id desc limit 1
        ) c on true
        where u.id is not null
    )
    select c.id, c.name, c.avatar_data, c.account_level, c.account_rank,
        case when c.followers_visibility = 'public' or c.id = auth.uid() or (
            c.followers_visibility = 'followers' and exists (
                select 1 from public.user_follows f where f.follower_id = auth.uid() and f.followed_id = c.id
            )
        ) then coalesce(c.follower_count_override::bigint,
            (select count(*) from public.user_follows f where f.followed_id = c.id)) else null end,
        case when c.email_visibility = 'public' then c.account_email::text else null end,
        coalesce((select exists(select 1 from public.user_follows f where f.follower_id = auth.uid() and f.followed_id = c.id)), false)
    from candidates c
    where (c.profile_visibility = 'public' or c.id = auth.uid() or (
        c.profile_visibility = 'followers' and exists (
            select 1 from public.user_follows f where f.follower_id = auth.uid() and f.followed_id = c.id
        )
    ))
    and (
        position(v_query in lower(c.name)) > 0
        or (c.email_visibility = 'public' and position(v_query in lower(coalesce(c.account_email,''))) > 0)
    )
    order by c.match_order, lower(c.name), c.id
    limit least(greatest(coalesce(p_limit,20),1),20)
    offset greatest(coalesce(p_offset,0),0);
end;
$$;

create or replace function public.get_public_profile_v2(p_user_id uuid)
returns table (
    user_id uuid, display_name text, avatar_data text, cover_data text, level integer, rank text,
    follower_count bigint, following_count bigint, is_following boolean, is_private boolean,
    joined_at timestamptz, equipped_frame_id text, favorites_visibility text,
    novel_favorites_visibility text, sources_visibility text, reading_stats_visibility text,
    followers_visibility text, following_visibility text, comments_visibility text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with target as (
        select u.id, p.*, u.created_at as auth_created_at,
            exists(select 1 from public.user_follows f where f.follower_id = auth.uid() and f.followed_id = u.id) as viewer_follows
        from auth.users u left join public.user_profiles p on p.user_id = u.id
        where u.id = p_user_id
    )
    select t.id,
        case when t.id = auth.uid() or t.profile_visibility = 'public' or
            (t.profile_visibility = 'followers' and t.viewer_follows)
            then coalesce(t.display_name, 'قارئ السلايم') else 'هذا الحساب خاص' end,
        case when t.id = auth.uid() or t.profile_visibility = 'public' or
            (t.profile_visibility = 'followers' and t.viewer_follows)
            then t.avatar_data else null end,
        case when (t.id = auth.uid() or t.profile_visibility = 'public' or
            (t.profile_visibility = 'followers' and t.viewer_follows)) and
            (t.cover_visibility = 'public' or t.id = auth.uid() or (t.cover_visibility = 'followers' and t.viewer_follows))
            then t.cover_data else null end,
        case when t.id = auth.uid() or t.profile_visibility = 'public' or
            (t.profile_visibility = 'followers' and t.viewer_follows) then coalesce(t.level,1) else null end,
        case when t.id = auth.uid() or t.profile_visibility = 'public' or
            (t.profile_visibility = 'followers' and t.viewer_follows) then coalesce(t.rank,'F') else null end,
        case when t.id = auth.uid() or t.followers_visibility = 'public' or
            (t.followers_visibility = 'followers' and t.viewer_follows)
            then coalesce(t.follower_count_override::bigint,(select count(*) from public.user_follows f where f.followed_id=t.id)) else null end,
        case when t.id = auth.uid() or t.following_visibility = 'public' or
            (t.following_visibility = 'followers' and t.viewer_follows)
            then (select count(*) from public.user_follows f where f.follower_id=t.id) else null end,
        t.viewer_follows,
        not (t.id = auth.uid() or t.profile_visibility = 'public' or (t.profile_visibility = 'followers' and t.viewer_follows)),
        case when t.id = auth.uid() or t.joined_at_visibility = 'public' or
            (t.joined_at_visibility = 'followers' and t.viewer_follows) then t.auth_created_at else null end,
        case when t.id = auth.uid() or t.equipped_frame_visibility = 'public' or
            (t.equipped_frame_visibility = 'followers' and t.viewer_follows) then t.equipped_frame_id else null end,
        t.favorites_visibility, t.novel_favorites_visibility, t.sources_visibility,
        t.reading_stats_visibility, t.followers_visibility, t.following_visibility, t.comments_visibility
    from target t;
$$;

revoke all on function public.update_my_privacy_settings(jsonb) from public, anon;
revoke all on function public.get_my_privacy_settings() from public, anon;
grant execute on function public.update_my_privacy_settings(jsonb) to authenticated;
grant execute on function public.get_my_privacy_settings() to authenticated;
revoke all on function public.search_public_users(text,integer,integer) from public;
grant execute on function public.search_public_users(text,integer,integer) to anon, authenticated;
revoke all on function public.get_public_profile_v2(uuid) from public;
grant execute on function public.get_public_profile_v2(uuid) to anon, authenticated;

-- Keep legacy public-profile and follow-list RPCs privacy-safe for older clients too.
create or replace function public.get_public_profile(p_user_id uuid)
returns table (
    user_id uuid, display_name text, avatar_data text, cover_data text, level integer, rank text,
    follower_count bigint, following_count bigint, is_following boolean
)
language sql stable security definer set search_path = public, pg_temp
as $$
    with target as (
        select u.id, p.*, exists(select 1 from public.user_follows f where f.follower_id=auth.uid() and f.followed_id=u.id) as viewer_follows
        from auth.users u left join public.user_profiles p on p.user_id=u.id where u.id=p_user_id
    )
    select t.id,
        case when t.id=auth.uid() or coalesce(t.profile_visibility,'public')='public' or (t.profile_visibility='followers' and t.viewer_follows)
            then coalesce(t.display_name,'قارئ السلايم') else 'هذا الحساب خاص' end,
        case when t.id=auth.uid() or coalesce(t.profile_visibility,'public')='public' or (t.profile_visibility='followers' and t.viewer_follows)
            then t.avatar_data else null end,
        case when (t.id=auth.uid() or coalesce(t.profile_visibility,'public')='public' or (t.profile_visibility='followers' and t.viewer_follows)) and
            (coalesce(t.cover_visibility,'public')='public' or t.id=auth.uid() or (t.cover_visibility='followers' and t.viewer_follows))
            then t.cover_data else null end,
        case when t.id=auth.uid() or coalesce(t.profile_visibility,'public')='public' or (t.profile_visibility='followers' and t.viewer_follows)
            then coalesce(t.level,1) else null end,
        case when t.id=auth.uid() or coalesce(t.profile_visibility,'public')='public' or (t.profile_visibility='followers' and t.viewer_follows)
            then coalesce(t.rank,'F') else null end,
        case when t.id=auth.uid() or coalesce(t.followers_visibility,'public')='public' or (t.followers_visibility='followers' and t.viewer_follows)
            then coalesce(t.follower_count_override::bigint,(select count(*) from public.user_follows f where f.followed_id=t.id)) else null end,
        case when t.id=auth.uid() or coalesce(t.following_visibility,'public')='public' or (t.following_visibility='followers' and t.viewer_follows)
            then (select count(*) from public.user_follows f where f.follower_id=t.id) else null end,
        t.viewer_follows
    from target t;
$$;

create or replace function public.list_followers(p_user_id uuid, p_limit integer default 50, p_offset integer default 0)
returns table (user_id uuid, display_name text, avatar_data text, level integer, rank text)
language sql stable security definer set search_path = public, pg_temp
as $$
    select f.follower_id, coalesce(p.display_name,c.author_name,'قارئ السلايم'), p.avatar_data,
        coalesce(p.level,c.level,1), coalesce(p.rank,c.rank,'F')
    from public.user_follows f left join public.user_profiles p on p.user_id=f.follower_id
    left join lateral (select cm.author_name,cm.level,cm.rank from public.comments cm where cm.user_id=f.follower_id and not cm.hidden and cm.parent_id is null order by cm.created_at desc limit 1) c on true
    where f.followed_id=p_user_id and exists(select 1 from public.user_profiles op where op.user_id=p_user_id and
        (op.followers_visibility='public' or p_user_id=auth.uid() or (op.followers_visibility='followers' and exists(select 1 from public.user_follows vf where vf.follower_id=auth.uid() and vf.followed_id=p_user_id))))
    order by f.created_at desc,f.follower_id limit least(greatest(coalesce(p_limit,50),1),100) offset greatest(coalesce(p_offset,0),0);
$$;

create or replace function public.list_following(p_user_id uuid, p_limit integer default 50, p_offset integer default 0)
returns table (user_id uuid, display_name text, avatar_data text, level integer, rank text)
language sql stable security definer set search_path = public, pg_temp
as $$
    select f.followed_id, coalesce(p.display_name,c.author_name,'قارئ السلايم'), p.avatar_data,
        coalesce(p.level,c.level,1), coalesce(p.rank,c.rank,'F')
    from public.user_follows f left join public.user_profiles p on p.user_id=f.followed_id
    left join lateral (select cm.author_name,cm.level,cm.rank from public.comments cm where cm.user_id=f.followed_id and not cm.hidden and cm.parent_id is null order by cm.created_at desc limit 1) c on true
    where f.follower_id=p_user_id and exists(select 1 from public.user_profiles op where op.user_id=p_user_id and
        (op.following_visibility='public' or p_user_id=auth.uid() or (op.following_visibility='followers' and exists(select 1 from public.user_follows vf where vf.follower_id=auth.uid() and vf.followed_id=p_user_id))))
    order by f.created_at desc,f.followed_id limit least(greatest(coalesce(p_limit,50),1),100) offset greatest(coalesce(p_offset,0),0);
$$;
