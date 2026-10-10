-- Sources and source preferences that must survive app reinstall after Google sign-in.
create table if not exists public.user_source_settings (
    user_id uuid not null references auth.users(id) on delete cascade,
    setting_type text not null check (setting_type in ('manga_preferences', 'novel_sources')),
    setting_key text not null,
    payload jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, setting_type, setting_key)
);

alter table public.user_source_settings enable row level security;

create policy user_source_settings_select_own on public.user_source_settings
    for select to authenticated using (user_id = auth.uid());
create policy user_source_settings_insert_own on public.user_source_settings
    for insert to authenticated with check (user_id = auth.uid());
create policy user_source_settings_update_own on public.user_source_settings
    for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy user_source_settings_delete_own on public.user_source_settings
    for delete to authenticated using (user_id = auth.uid());

grant select, insert, update, delete on public.user_source_settings to authenticated;
