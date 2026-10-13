-- Community Novels: additive schema for Manhwa Slime.
-- No existing table or data is removed or altered.
create extension if not exists pg_trgm;

create table if not exists public.community_novels (
    id uuid primary key default gen_random_uuid(),
    author_id uuid not null references auth.users(id) on delete cascade,
    title text not null check (char_length(trim(title)) between 1 and 120),
    description text not null default '' check (char_length(description) <= 5000),
    cover_path text,
    language text not null default 'ar' check (language in ('ar','en','fr','other')),
    primary_category text not null default 'fantasy' check (primary_category in ('fantasy','romance','action','adventure','mystery','horror','drama','sci_fi','comedy','historical','other')),
    tags text[] not null default '{}',
    content_notice text not null default 'general' check (content_notice in ('general','teen','mature')),
    completion_status text not null default 'ongoing' check (completion_status in ('ongoing','completed')),
    status text not null default 'draft' check (status in ('draft','published','hidden','under_review')),
    published_at timestamptz,
    view_count bigint not null default 0 check (view_count >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.community_novel_chapters (
    id uuid primary key default gen_random_uuid(),
    novel_id uuid not null references public.community_novels(id) on delete cascade,
    chapter_number integer not null check (chapter_number > 0),
    title text not null check (char_length(trim(title)) between 1 and 160),
    content text not null default '' check (char_length(content) <= 1000000),
    status text not null default 'draft' check (status in ('draft','published','hidden','under_review')),
    published_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (novel_id, chapter_number)
);

create table if not exists public.community_novel_library (
    user_id uuid not null references auth.users(id) on delete cascade,
    novel_id uuid not null references public.community_novels(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, novel_id)
);

create table if not exists public.community_reading_progress (
    user_id uuid not null references auth.users(id) on delete cascade,
    novel_id uuid not null references public.community_novels(id) on delete cascade,
    chapter_id uuid references public.community_novel_chapters(id) on delete set null,
    reading_position integer not null default 0 check (reading_position >= 0),
    updated_at timestamptz not null default now(),
    primary key (user_id, novel_id)
);

create table if not exists public.community_novel_likes (
    user_id uuid not null references auth.users(id) on delete cascade,
    novel_id uuid not null references public.community_novels(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, novel_id)
);

create table if not exists public.community_novel_ratings (
    user_id uuid not null references auth.users(id) on delete cascade,
    novel_id uuid not null references public.community_novels(id) on delete cascade,
    rating smallint not null check (rating between 1 and 5),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, novel_id)
);

create table if not exists public.community_comments (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    novel_id uuid not null references public.community_novels(id) on delete cascade,
    chapter_id uuid references public.community_novel_chapters(id) on delete cascade,
    parent_id uuid references public.community_comments(id) on delete cascade,
    content text not null check (char_length(trim(content)) between 1 and 1000),
    status text not null default 'visible' check (status in ('visible','hidden','reported')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.community_author_follows (
    follower_id uuid not null references auth.users(id) on delete cascade,
    author_id uuid not null references auth.users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (follower_id, author_id),
    check (follower_id <> author_id)
);

create table if not exists public.community_notifications (
    id uuid primary key default gen_random_uuid(),
    recipient_id uuid not null references auth.users(id) on delete cascade,
    actor_id uuid references auth.users(id) on delete set null,
    notification_type text not null check (notification_type in ('chapter','comment','reply','like','follow','moderation','system')),
    entity_id uuid,
    payload jsonb not null default '{}',
    is_read boolean not null default false,
    created_at timestamptz not null default now(),
    unique (recipient_id, notification_type, entity_id, actor_id)
);

create table if not exists public.community_reports (
    id uuid primary key default gen_random_uuid(),
    reporter_id uuid not null references auth.users(id) on delete cascade,
    novel_id uuid references public.community_novels(id) on delete cascade,
    chapter_id uuid references public.community_novel_chapters(id) on delete cascade,
    comment_id uuid references public.community_comments(id) on delete cascade,
    reason text not null check (reason in ('copyright','abuse','spam','inappropriate','other')),
    description text not null default '' check (char_length(description) <= 1000),
    status text not null default 'open' check (status in ('open','reviewing','resolved','dismissed')),
    created_at timestamptz not null default now(),
    check (num_nonnulls(novel_id, chapter_id, comment_id) = 1)
);

create index if not exists community_novels_public_sort_idx on public.community_novels (status, updated_at desc) where status = 'published';
create index if not exists community_novels_author_idx on public.community_novels (author_id, updated_at desc);
create index if not exists community_novels_category_idx on public.community_novels (primary_category, updated_at desc);
create index if not exists community_novels_title_trgm_idx on public.community_novels using gin (title gin_trgm_ops);
create index if not exists community_chapters_public_idx on public.community_novel_chapters (novel_id, chapter_number) where status = 'published';
create index if not exists community_comments_novel_idx on public.community_comments (novel_id, created_at desc);
create index if not exists community_notifications_recipient_idx on public.community_notifications (recipient_id, is_read, created_at desc);
create index if not exists community_follows_author_idx on public.community_author_follows (author_id, created_at desc);

alter table public.community_novels enable row level security;
alter table public.community_novel_chapters enable row level security;
alter table public.community_novel_library enable row level security;
alter table public.community_reading_progress enable row level security;
alter table public.community_novel_likes enable row level security;
alter table public.community_novel_ratings enable row level security;
alter table public.community_comments enable row level security;
alter table public.community_author_follows enable row level security;
alter table public.community_notifications enable row level security;
alter table public.community_reports enable row level security;

-- Public reads expose only published novels and chapters. Authors can manage their own work.
drop policy if exists community_novels_public_read on public.community_novels;
create policy community_novels_public_read on public.community_novels for select using (status = 'published' or author_id = auth.uid());
drop policy if exists community_novels_insert_own on public.community_novels;
create policy community_novels_insert_own on public.community_novels for insert to authenticated with check (author_id = auth.uid());
drop policy if exists community_novels_update_own on public.community_novels;
create policy community_novels_update_own on public.community_novels for update to authenticated using (author_id = auth.uid()) with check (author_id = auth.uid());
drop policy if exists community_novels_delete_own on public.community_novels;
create policy community_novels_delete_own on public.community_novels for delete to authenticated using (author_id = auth.uid());

drop policy if exists community_chapters_public_read on public.community_novel_chapters;
create policy community_chapters_public_read on public.community_novel_chapters for select using (status = 'published' or exists (select 1 from public.community_novels n where n.id = novel_id and n.author_id = auth.uid()));
drop policy if exists community_chapters_insert_own on public.community_novel_chapters;
create policy community_chapters_insert_own on public.community_novel_chapters for insert to authenticated with check (exists (select 1 from public.community_novels n where n.id = novel_id and n.author_id = auth.uid()));
drop policy if exists community_chapters_update_own on public.community_novel_chapters;
create policy community_chapters_update_own on public.community_novel_chapters for update to authenticated using (exists (select 1 from public.community_novels n where n.id = novel_id and n.author_id = auth.uid())) with check (exists (select 1 from public.community_novels n where n.id = novel_id and n.author_id = auth.uid()));
drop policy if exists community_chapters_delete_own on public.community_novel_chapters;
create policy community_chapters_delete_own on public.community_novel_chapters for delete to authenticated using (exists (select 1 from public.community_novels n where n.id = novel_id and n.author_id = auth.uid()));

-- Personal data policies.
drop policy if exists community_library_own on public.community_novel_library;
create policy community_library_own on public.community_novel_library for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists community_progress_own on public.community_reading_progress;
create policy community_progress_own on public.community_reading_progress for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists community_likes_read on public.community_novel_likes;
create policy community_likes_read on public.community_novel_likes for select using (true);
drop policy if exists community_likes_own on public.community_novel_likes;
create policy community_likes_own on public.community_novel_likes for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists community_ratings_read on public.community_novel_ratings;
create policy community_ratings_read on public.community_novel_ratings for select using (true);
drop policy if exists community_ratings_own on public.community_novel_ratings;
create policy community_ratings_own on public.community_novel_ratings for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists community_comments_read on public.community_comments;
create policy community_comments_read on public.community_comments for select using (status = 'visible');
drop policy if exists community_comments_own on public.community_comments;
create policy community_comments_own on public.community_comments for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists community_follows_read on public.community_author_follows;
create policy community_follows_read on public.community_author_follows for select using (true);
drop policy if exists community_follows_own on public.community_author_follows;
create policy community_follows_own on public.community_author_follows for all to authenticated using (follower_id = auth.uid()) with check (follower_id = auth.uid() and follower_id <> author_id);
drop policy if exists community_notifications_own on public.community_notifications;
create policy community_notifications_own on public.community_notifications for all to authenticated using (recipient_id = auth.uid()) with check (recipient_id = auth.uid());
drop policy if exists community_reports_own on public.community_reports;
create policy community_reports_own on public.community_reports for insert to authenticated with check (reporter_id = auth.uid());

-- Atomic view increment, scoped to published content and safe against client-supplied counters.
create or replace function public.community_increment_view(p_novel_id uuid)
returns void language plpgsql security invoker set search_path = public as $$
begin
  update public.community_novels set view_count = view_count + 1, updated_at = updated_at
  where id = p_novel_id and status = 'published';
end;
$$;
revoke all on function public.community_increment_view(uuid) from public, anon;
grant execute on function public.community_increment_view(uuid) to authenticated;

-- Keep timestamps and publication dates coherent.
create or replace function public.community_set_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end; $$;
drop trigger if exists community_novels_updated_at on public.community_novels;
create trigger community_novels_updated_at before update on public.community_novels for each row execute function public.community_set_updated_at();
drop trigger if exists community_chapters_updated_at on public.community_novel_chapters;
create trigger community_chapters_updated_at before update on public.community_novel_chapters for each row execute function public.community_set_updated_at();
drop trigger if exists community_comments_updated_at on public.community_comments;
create trigger community_comments_updated_at before update on public.community_comments for each row execute function public.community_set_updated_at();
