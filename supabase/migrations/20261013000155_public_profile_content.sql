-- Public profile content is served through bounded SECURITY DEFINER RPCs only.
-- Raw user_favorites and view history remain owner-only under their existing RLS.

create or replace function public.get_public_favorites(
    p_user_id uuid, p_item_type text, p_limit integer default 20, p_offset integer default 0
)
returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp
as $$
declare v_visibility text; v_base text; v_allowed boolean; v_items jsonb;
begin
    if p_item_type not in ('manga','novel') then raise exception 'invalid favorite type'; end if;
    select coalesce(profile_visibility,'public'),
        case when p_item_type='novel' then coalesce(novel_favorites_visibility,'private') else coalesce(favorites_visibility,'private') end
    into v_base,v_visibility from public.user_profiles where user_id=p_user_id;
    if not found then v_base:='public'; v_visibility:='private'; end if;
    v_allowed := p_user_id=auth.uid() or
        (v_base='public' and v_visibility='public') or
        (v_base in ('public','followers') and v_visibility='followers' and exists(
            select 1 from public.user_follows f where f.follower_id=auth.uid() and f.followed_id=p_user_id
        ));
    if not v_allowed then return jsonb_build_object('allowed',false,'items','[]'::jsonb); end if;
    select coalesce(jsonb_agg(jsonb_build_object('title',x.title,'cover',x.cover,'added_at',x.added_at) order by x.added_at desc),'[]'::jsonb)
    into v_items
    from (
        select f.title,f.cover,f.created_at,f.added_at from public.user_favorites f
        where f.user_id=p_user_id and f.item_type=p_item_type
        order by f.added_at desc,f.item_key limit least(greatest(coalesce(p_limit,20),1),50) offset greatest(coalesce(p_offset,0),0)
    ) x;
    return jsonb_build_object('allowed',true,'items',v_items);
end;
$$;

create or replace function public.get_public_reading_stats(p_user_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp
as $$
declare v_base text; v_visibility text; v_allowed boolean;
begin
    select coalesce(profile_visibility,'public'),coalesce(reading_stats_visibility,'private')
      into v_base,v_visibility from public.user_profiles where user_id=p_user_id;
    if not found then v_base:='public'; v_visibility:='private'; end if;
    v_allowed := p_user_id=auth.uid() or
      (v_base='public' and v_visibility='public') or
      (v_base in ('public','followers') and v_visibility='followers' and exists(
        select 1 from public.user_follows f where f.follower_id=auth.uid() and f.followed_id=p_user_id));
    if not v_allowed then return jsonb_build_object('allowed',false); end if;
    return jsonb_build_object(
      'allowed',true,
      'titles_viewed',(select count(distinct v.title_key) from public.title_views v where v.user_id=p_user_id),
      'active_days',(select count(distinct v.view_day) from public.title_views v where v.user_id=p_user_id),
      'ratings_count',(select count(*) from public.title_ratings r where r.user_id=p_user_id)
    );
end;
$$;

create or replace function public.get_public_user_comments(p_user_id uuid,p_limit integer default 20,p_offset integer default 0)
returns table(id uuid,title_key text,body text,created_at timestamptz,is_spoiler boolean,likes integer,dislikes integer)
language plpgsql stable security definer set search_path = public, pg_temp
as $$
declare v_base text; v_visibility text; v_allowed boolean;
begin
    select coalesce(profile_visibility,'public'),coalesce(comments_visibility,'private')
      into v_base,v_visibility from public.user_profiles where user_id=p_user_id;
    if not found then v_base:='public'; v_visibility:='private'; end if;
    v_allowed := p_user_id=auth.uid() or
      (v_base='public' and v_visibility='public') or
      (v_base in ('public','followers') and v_visibility='followers' and exists(
        select 1 from public.user_follows f where f.follower_id=auth.uid() and f.followed_id=p_user_id));
    if not v_allowed then return; end if;
    return query select c.id,c.title_key,c.body,c.created_at,c.is_spoiler,c.likes,c.dislikes
      from public.comments c where c.user_id=p_user_id and c.hidden=false and c.parent_id is null
      order by c.created_at desc,c.id desc limit least(greatest(coalesce(p_limit,20),1),50) offset greatest(coalesce(p_offset,0),0);
end;
$$;

revoke all on function public.get_public_favorites(uuid,text,integer,integer) from public;
revoke all on function public.get_public_reading_stats(uuid) from public;
revoke all on function public.get_public_user_comments(uuid,integer,integer) from public;
grant execute on function public.get_public_favorites(uuid,text,integer,integer) to anon,authenticated;
grant execute on function public.get_public_reading_stats(uuid) to anon,authenticated;
grant execute on function public.get_public_user_comments(uuid,integer,integer) to anon,authenticated;
