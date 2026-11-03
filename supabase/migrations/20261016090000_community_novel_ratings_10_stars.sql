-- Expand community novel ratings to 10 stars and expose only aggregate stats.
-- Existing 1-5 star ratings remain valid and are not rewritten.
alter table public.community_novel_ratings
    drop constraint if exists community_novel_ratings_rating_check;
alter table public.community_novel_ratings
    add constraint community_novel_ratings_rating_check check (rating between 1 and 10);

create or replace function public.community_set_novel_rating(p_novel_id uuid, p_rating smallint)
returns boolean
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception 'authentication required' using errcode = '42501';
    end if;
    if p_rating is null or p_rating < 1 or p_rating > 10 then
        raise exception 'rating must be between 1 and 10' using errcode = '22023';
    end if;
    insert into public.community_novel_ratings (user_id, novel_id, rating)
    values (v_user_id, p_novel_id, p_rating)
    on conflict (user_id, novel_id)
    do update set rating = excluded.rating, updated_at = now();
    return true;
end;
$$;

create or replace function public.community_novel_rating_stats(p_novel_id uuid)
returns table (average_rating numeric, rating_count bigint, my_rating smallint)
language sql
stable
security invoker
set search_path = public
as $$
    select
        coalesce(round(avg(r.rating)::numeric, 1), 0::numeric),
        count(*)::bigint,
        coalesce(max(r.rating) filter (where r.user_id = auth.uid()), 0::smallint)
    from public.community_novel_ratings as r
    where r.novel_id = p_novel_id;
$$;

revoke all on function public.community_set_novel_rating(uuid, smallint) from public;
revoke all on function public.community_novel_rating_stats(uuid) from public;
grant execute on function public.community_set_novel_rating(uuid, smallint) to authenticated;
grant execute on function public.community_novel_rating_stats(uuid) to anon, authenticated;

create or replace function public.community_novel_like_stats(p_novel_id uuid)
returns table (like_count bigint, liked boolean)
language sql
stable
security invoker
set search_path = public
as $$
    select count(*)::bigint, coalesce(bool_or(l.user_id = auth.uid()), false)
    from public.community_novel_likes as l
    where l.novel_id = p_novel_id;
$$;

revoke all on function public.community_novel_like_stats(uuid) from public;
grant execute on function public.community_novel_like_stats(uuid) to anon, authenticated;
