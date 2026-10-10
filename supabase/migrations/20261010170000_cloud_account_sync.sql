-- Cloud account state: profile fields and favorites survive app reinstall after Google sign-in.
create table if not exists public.user_profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    display_name text not null default 'قارئ السلايم' check (length(display_name) between 1 and 80),
    avatar_data text,
    cover_data text,
    level integer not null default 1 check (level >= 1),
    rank text not null default 'F' check (rank in ('F','E','D','C','B','A','S','SS','SS+')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.user_favorites (
    user_id uuid not null references auth.users(id) on delete cascade,
    item_type text not null check (item_type in ('manga','novel')),
    item_key text not null check (length(item_key) between 1 and 500),
    title text not null default '',
    cover text,
    source_id text,
    added_at bigint not null default (extract(epoch from now()) * 1000)::bigint,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, item_type, item_key)
);

create index if not exists user_favorites_user_idx on public.user_favorites(user_id, item_type);

alter table public.user_profiles enable row level security;
alter table public.user_favorites enable row level security;

create policy user_profiles_select_own on public.user_profiles
    for select to authenticated using (user_id = auth.uid());
create policy user_profiles_insert_own on public.user_profiles
    for insert to authenticated with check (user_id = auth.uid());
create policy user_profiles_update_own on public.user_profiles
    for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy user_favorites_select_own on public.user_favorites
    for select to authenticated using (user_id = auth.uid());
create policy user_favorites_insert_own on public.user_favorites
    for insert to authenticated with check (user_id = auth.uid());
create policy user_favorites_update_own on public.user_favorites
    for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy user_favorites_delete_own on public.user_favorites
    for delete to authenticated using (user_id = auth.uid());

grant select, insert, update on public.user_profiles to authenticated;
grant select, insert, update, delete on public.user_favorites to authenticated;
