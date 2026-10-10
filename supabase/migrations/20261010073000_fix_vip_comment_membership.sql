-- Correct the outer-join null case: only a matched, active membership is VIP.
create or replace function public.list_comments_with_vip(p_title_key text, p_sort integer default 0)
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
    is_pinned boolean
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with visible_comments as (
        select c.id, c.user_id, c.author_name, c.body, c.created_at, c.is_spoiler, c.level, c.rank,
            c.author_avatar, c.likes, c.dislikes,
            (m.user_id is not null and (m.expires_at is null or m.expires_at > now())) as member_vip,
            row_number() over (partition by c.user_id order by c.created_at desc, c.id desc) as vip_position
        from public.comments c
        left join public.vip_memberships m on m.user_id = c.user_id
        where c.title_key = p_title_key and c.hidden = false
    )
    select v.id, v.user_id, v.author_name, v.body, v.created_at, v.is_spoiler, v.level, v.rank,
        v.author_avatar, v.likes, v.dislikes, v.member_vip,
        (v.member_vip and v.vip_position = 1) as is_pinned
    from visible_comments v
    order by (v.member_vip and v.vip_position = 1) desc,
        case when p_sort = 1 then v.likes end desc nulls last,
        v.created_at desc
    limit 50;
$$;
