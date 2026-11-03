-- User-to-user follow relationships, public-safe profile queries, and one-level comment replies.
-- Existing profile data and comments are preserved.

create table if not exists public.user_follows (
    follower_id uuid not null references auth.users(id) on delete cascade,
    followed_id uuid not null references auth.users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (follower_id, followed_id),
    constraint user_follows_no_self_follow check (follower_id <> followed_id)
);

create index if not exists user_follows_followed_created_idx
    on public.user_follows (followed_id, created_at desc);
create index if not exists user_follows_follower_created_idx
    on public.user_follows (follower_id, created_at desc);

alter table public.user_follows enable row level security;
revoke all on public.user_follows from public, anon, authenticated;

alter table public.comments
    add column if not exists parent_id uuid references public.comments(id) on delete cascade;
create index if not exists comments_parent_created_idx
    on public.comments (parent_id, created_at asc)
    where parent_id is not null;

create or replace function public.validate_comment_parent()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if new.parent_id is not null and not exists (
        select 1
        from public.comments parent
        where parent.id = new.parent_id
          and parent.parent_id is null
          and parent.hidden = false
    ) then
        raise exception 'reply target must be a visible top-level comment';
    end if;
    return new;
end;
$$;

drop trigger if exists comments_validate_parent on public.comments;
create trigger comments_validate_parent
    before insert or update on public.comments
    for each row execute function public.validate_comment_parent();

create or replace function public.follow_user(p_followed_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
        raise exception 'Google sign-in required';
    end if;
    if p_followed_id is null or p_followed_id = v_user then
        raise exception 'cannot follow this user';
    end if;
    if not exists (select 1 from auth.users where id = p_followed_id) then
        raise exception 'user not found';
    end if;
    insert into public.user_follows (follower_id, followed_id)
    values (v_user, p_followed_id)
    on conflict (follower_id, followed_id) do nothing;
    return true;
end;
$$;

create or replace function public.unfollow_user(p_followed_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
        raise exception 'Google sign-in required';
    end if;
    if p_followed_id is null or p_followed_id = v_user then
        raise exception 'cannot unfollow this user';
    end if;
    delete from public.user_follows
    where follower_id = v_user and followed_id = p_followed_id;
    return true;
end;
$$;

create or replace function public.get_public_profile(p_user_id uuid)
returns table (
    user_id uuid,
    display_name text,
    avatar_data text,
    cover_data text,
    level integer,
    rank text,
    follower_count bigint,
    following_count bigint,
    is_following boolean
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with latest_comment as (
        select c.author_name, c.author_avatar, c.level, c.rank
        from public.comments c
        where c.user_id = p_user_id and c.hidden = false and c.parent_id is null
        order by c.created_at desc, c.id desc
        limit 1
    )
    select
        u.id,
        coalesce(p.display_name, c.author_name, 'قارئ السلايم'),
        coalesce(p.avatar_data, c.author_avatar),
        p.cover_data,
        coalesce(p.level, c.level, 1),
        coalesce(p.rank, c.rank, 'F'),
        (select count(*) from public.user_follows f where f.followed_id = u.id),
        (select count(*) from public.user_follows f where f.follower_id = u.id),
        coalesce((select exists (
            select 1 from public.user_follows f
            where f.follower_id = auth.uid() and f.followed_id = u.id
        )), false)
    from auth.users u
    left join public.user_profiles p on p.user_id = u.id
    left join latest_comment c on true
    where u.id = p_user_id;
$$;

create or replace function public.list_followers(
    p_user_id uuid,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    user_id uuid,
    display_name text,
    avatar_data text,
    level integer,
    rank text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        f.follower_id,
        coalesce(p.display_name, c.author_name, 'قارئ السلايم'),
        coalesce(p.avatar_data, c.author_avatar),
        coalesce(p.level, c.level, 1),
        coalesce(p.rank, c.rank, 'F')
    from public.user_follows f
    left join public.user_profiles p on p.user_id = f.follower_id
    left join lateral (
        select cm.author_name, cm.author_avatar, cm.level, cm.rank
        from public.comments cm
        where cm.user_id = f.follower_id and cm.hidden = false and cm.parent_id is null
        order by cm.created_at desc, cm.id desc
        limit 1
    ) c on true
    where f.followed_id = p_user_id
    order by f.created_at desc, f.follower_id
    limit least(greatest(coalesce(p_limit, 50), 1), 100)
    offset greatest(coalesce(p_offset, 0), 0);
$$;

create or replace function public.list_following(
    p_user_id uuid,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    user_id uuid,
    display_name text,
    avatar_data text,
    level integer,
    rank text
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select
        f.followed_id,
        coalesce(p.display_name, c.author_name, 'قارئ السلايم'),
        coalesce(p.avatar_data, c.author_avatar),
        coalesce(p.level, c.level, 1),
        coalesce(p.rank, c.rank, 'F')
    from public.user_follows f
    left join public.user_profiles p on p.user_id = f.followed_id
    left join lateral (
        select cm.author_name, cm.author_avatar, cm.level, cm.rank
        from public.comments cm
        where cm.user_id = f.followed_id and cm.hidden = false and cm.parent_id is null
        order by cm.created_at desc, cm.id desc
        limit 1
    ) c on true
    where f.follower_id = p_user_id
    order by f.created_at desc, f.followed_id
    limit least(greatest(coalesce(p_limit, 50), 1), 100)
    offset greatest(coalesce(p_offset, 0), 0);
$$;

-- Limit public RPCs to profile-safe fields; private user data, favorites and account email are never returned.
revoke all on function public.get_public_profile(uuid) from public;
revoke all on function public.list_followers(uuid, integer, integer) from public;
revoke all on function public.list_following(uuid, integer, integer) from public;
grant execute on function public.get_public_profile(uuid) to anon, authenticated;
grant execute on function public.list_followers(uuid, integer, integer) to anon, authenticated;
grant execute on function public.list_following(uuid, integer, integer) to anon, authenticated;

revoke all on function public.follow_user(uuid) from public, anon;
revoke all on function public.unfollow_user(uuid) from public, anon;
grant execute on function public.follow_user(uuid) to authenticated;
grant execute on function public.unfollow_user(uuid) to authenticated;

-- Keep the existing RPC intact for older app versions. New builds use this extended response.
create or replace function public.list_comments_with_replies(p_title_key text, p_sort integer default 0)
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
    is_pinned boolean,
    parent_id uuid
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with root_base as (
        select
            c.id, c.user_id, c.author_name, c.body, c.created_at, c.is_spoiler,
            c.level, c.rank, c.author_avatar, c.likes, c.dislikes,
            coalesce(m.user_id is not null and (m.expires_at is null or m.expires_at > now()), false) as member_vip,
            row_number() over (partition by c.user_id order by c.created_at desc, c.id desc) as vip_position
        from public.comments c
        left join public.vip_memberships m on m.user_id = c.user_id
        where c.title_key = p_title_key and c.hidden = false and c.parent_id is null
    ),
    roots as (
        select
            r.*,
            (r.member_vip and r.vip_position = 1) as pinned,
            row_number() over (
                order by (r.member_vip and r.vip_position = 1) desc,
                    case when p_sort = 1 then r.likes end desc nulls last,
                    r.created_at desc, r.id desc
            ) as root_position
        from root_base r
        order by (r.member_vip and r.vip_position = 1) desc,
            case when p_sort = 1 then r.likes end desc nulls last,
            r.created_at desc, r.id desc
        limit 50
    ),
    thread_rows as (
        select
            r.id, r.user_id, r.author_name, r.body, r.created_at, r.is_spoiler,
            r.level, r.rank, r.author_avatar, r.likes, r.dislikes,
            r.member_vip as is_vip, r.pinned as is_pinned, null::uuid as parent_id,
            r.root_position, 0 as row_kind
        from roots r
        union all
        select
            reply.id, reply.user_id, reply.author_name, reply.body, reply.created_at, reply.is_spoiler,
            reply.level, reply.rank, reply.author_avatar, reply.likes, reply.dislikes,
            coalesce(m.user_id is not null and (m.expires_at is null or m.expires_at > now()), false) as is_vip,
            false as is_pinned, reply.parent_id,
            r.root_position, 1 as row_kind
        from roots r
        join lateral (
            select c.*
            from public.comments c
            where c.parent_id = r.id and c.hidden = false
            order by c.created_at desc, c.id desc
            limit 10
        ) reply on true
        left join public.vip_memberships m on m.user_id = reply.user_id
    )
    select
        t.id, t.user_id, t.author_name, t.body, t.created_at, t.is_spoiler,
        t.level, t.rank, t.author_avatar, t.likes, t.dislikes,
        t.is_vip, t.is_pinned, t.parent_id
    from thread_rows t
    order by t.root_position, t.row_kind, t.created_at, t.id;
$$;

revoke all on function public.list_comments_with_replies(text, integer) from public;
grant execute on function public.list_comments_with_replies(text, integer) to anon, authenticated;
