create table if not exists public.achievement_definitions (
    id text primary key check (id ~ '^[a-z0-9][a-z0-9_-]{0,79}$'),
    name text not null check (char_length(name) between 1 and 120),
    description text not null default '' check (char_length(description) <= 500),
    metric text not null check (metric in ('read_chapters', 'unlocked_chapters')),
    target_count integer not null check (target_count > 0 and target_count <= 1000000),
    title_label text not null check (char_length(title_label) between 1 and 80),
    active boolean not null default true,
    sort_order integer not null default 0
);

create table if not exists public.user_achievements (
    user_id uuid not null references auth.users(id) on delete cascade,
    achievement_id text not null references public.achievement_definitions(id) on delete restrict,
    claimed_at timestamptz not null default now(),
    primary key (user_id, achievement_id)
);

create table if not exists public.user_equipped_titles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    achievement_id text not null references public.achievement_definitions(id) on delete restrict,
    updated_at timestamptz not null default now()
);

alter table public.achievement_definitions enable row level security;
alter table public.user_achievements enable row level security;
alter table public.user_equipped_titles enable row level security;
revoke all on public.achievement_definitions, public.user_achievements, public.user_equipped_titles from anon, authenticated;
grant select on public.user_achievements, public.user_equipped_titles to authenticated;
drop policy if exists user_achievements_select_own on public.user_achievements;
create policy user_achievements_select_own on public.user_achievements for select to authenticated using (user_id = auth.uid());
drop policy if exists user_equipped_titles_select_own on public.user_equipped_titles;
create policy user_equipped_titles_select_own on public.user_equipped_titles for select to authenticated using (user_id = auth.uid());

insert into public.achievement_definitions(id, name, description, metric, target_count, title_label, sort_order)
values
    ('read_50', 'قارئ مثابر', 'أكمل قراءة 50 فصل مانهوا مختلفًا.', 'read_chapters', 50, 'قارئ مثابر', 10),
    ('read_100', 'قارئ أسطوري', 'أكمل قراءة 100 فصل مانهوا مختلف.', 'read_chapters', 100, 'قارئ أسطوري', 20),
    ('unlock_10', 'فاتح الفصول', 'افتح 10 فصول باستخدام العملات.', 'unlocked_chapters', 10, 'فاتح الفصول', 30),
    ('unlock_50', 'سيّد الفصول', 'افتح 50 فصلًا باستخدام العملات.', 'unlocked_chapters', 50, 'سيّد الفصول', 40)
on conflict (id) do nothing;

create or replace function public.get_achievements()
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_items jsonb;
    v_equipped_id text;
    v_equipped_label text;
begin
    if v_user is null then
        raise exception 'authentication required' using errcode = '42501';
    end if;
    select e.achievement_id, d.title_label
      into v_equipped_id, v_equipped_label
      from public.user_equipped_titles e
      join public.achievement_definitions d on d.id = e.achievement_id
     where e.user_id = v_user;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', d.id,
        'name', d.name,
        'description', d.description,
        'metric', d.metric,
        'target', d.target_count,
        'progress', case d.metric
            when 'read_chapters' then (
                select count(distinct c.chapter_key)::integer
                  from public.reading_task_completions c where c.user_id = v_user
            )
            when 'unlocked_chapters' then (
                select count(*)::integer from public.chapter_unlocks u where u.user_id = v_user
            )
            else 0
        end,
        'claimed', exists (
            select 1 from public.user_achievements a
             where a.user_id = v_user and a.achievement_id = d.id
        ),
        'title', d.title_label,
        'equipped', v_equipped_id = d.id
    ) order by d.sort_order, d.id), '[]'::jsonb)
      into v_items
      from public.achievement_definitions d
     where d.active;

    return jsonb_build_object(
        'achievements', v_items,
        'equipped_title_id', v_equipped_id,
        'equipped_title', v_equipped_label
    );
end;
$$;
revoke all on function public.get_achievements() from public, anon;
grant execute on function public.get_achievements() to authenticated;

create or replace function public.claim_achievement(p_achievement_id text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_def public.achievement_definitions%rowtype;
    v_progress integer;
    v_inserted text;
begin
    if v_user is null then
        raise exception 'authentication required' using errcode = '42501';
    end if;
    select * into v_def
      from public.achievement_definitions d
     where d.id = p_achievement_id and d.active
     for share;
    if not found then raise exception 'achievement unavailable'; end if;

    if v_def.metric = 'read_chapters' then
        select count(distinct c.chapter_key)::integer into v_progress
          from public.reading_task_completions c where c.user_id = v_user;
    else
        select count(*)::integer into v_progress
          from public.chapter_unlocks u where u.user_id = v_user;
    end if;
    if v_progress < v_def.target_count then
        raise exception 'achievement requirement not reached';
    end if;

    insert into public.user_achievements(user_id, achievement_id)
    values (v_user, v_def.id)
    on conflict (user_id, achievement_id) do nothing
    returning achievement_id into v_inserted;

    return jsonb_build_object(
        'claimed', v_inserted is not null,
        'already_claimed', v_inserted is null,
        'achievement_id', v_def.id,
        'title', v_def.title_label,
        'progress', v_progress,
        'target', v_def.target_count
    );
end;
$$;
revoke all on function public.claim_achievement(text) from public, anon;
grant execute on function public.claim_achievement(text) to authenticated;

create or replace function public.equip_achievement_title(p_achievement_id text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_title text;
begin
    if v_user is null then
        raise exception 'authentication required' using errcode = '42501';
    end if;
    select d.title_label into v_title
      from public.user_achievements a
      join public.achievement_definitions d on d.id = a.achievement_id
     where a.user_id = v_user
       and a.achievement_id = p_achievement_id
       and d.active;
    if v_title is null then raise exception 'title has not been earned'; end if;

    insert into public.user_equipped_titles(user_id, achievement_id, updated_at)
    values (v_user, p_achievement_id, now())
    on conflict (user_id) do update
       set achievement_id = excluded.achievement_id,
           updated_at = now();
    return jsonb_build_object('equipped', true, 'achievement_id', p_achievement_id, 'title', v_title);
end;
$$;
revoke all on function public.equip_achievement_title(text) from public, anon;
grant execute on function public.equip_achievement_title(text) to authenticated;
