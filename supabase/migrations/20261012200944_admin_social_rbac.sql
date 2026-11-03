-- Server-enforced roles and moderation primitives for Manhwa Slime.
-- The only owner is fixed to an internal auth.users UUID; owner cannot be assigned by app users.

create table if not exists public.admin_roles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    role text not null check (role in ('owner', 'admin', 'moderator', 'support', 'user')),
    granted_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default now(),
    revoked_at timestamptz
);

create table if not exists public.admin_permissions (
    role text not null check (role in ('owner', 'admin', 'moderator', 'support', 'user')),
    permission text not null check (length(permission) between 1 and 80),
    created_at timestamptz not null default now(),
    primary key (role, permission)
);

create table if not exists public.admin_audit_logs (
    id uuid primary key default gen_random_uuid(),
    actor_id uuid references auth.users(id) on delete set null,
    action text not null,
    target_type text not null,
    target_id text,
    reason text,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists public.moderation_actions (
    id uuid primary key default gen_random_uuid(),
    moderator_id uuid references auth.users(id) on delete set null,
    target_user_id uuid not null references auth.users(id) on delete cascade,
    target_type text not null check (target_type in ('user', 'comment')),
    target_id text,
    action text not null check (action in ('mute', 'ban', 'unban')),
    reason text not null check (length(trim(reason)) between 1 and 500),
    expires_at timestamptz,
    created_at timestamptz not null default now()
);

-- Extend the existing reports table; do not create a duplicate report system.
alter table public.reports add column if not exists description text not null default '';
alter table public.reports add column if not exists status text not null default 'open';
alter table public.reports add column if not exists resolved_by uuid references auth.users(id) on delete set null;
alter table public.reports add column if not exists resolution_note text;
alter table public.reports add column if not exists resolved_at timestamptz;
alter table public.reports add column if not exists updated_at timestamptz not null default now();
alter table public.reports add constraint reports_status_check check (status in ('open', 'reviewing', 'resolved', 'dismissed'));
alter table public.reports add constraint reports_reason_check check (reason is null or reason in ('abuse', 'inappropriate', 'bullying', 'spam', 'malicious_link', 'spoiler', 'other'));
alter table public.reports add constraint reports_description_length_check check (char_length(description) <= 1000);
create unique index if not exists reports_one_per_user_comment_idx on public.reports(comment_id, reporter_id);

create table if not exists public.notifications (
    id uuid primary key default gen_random_uuid(),
    recipient_id uuid references auth.users(id) on delete cascade,
    title text not null check (length(trim(title)) between 1 and 120),
    body text not null check (length(trim(body)) between 1 and 2000),
    notification_type text not null check (notification_type in ('announcement', 'system', 'maintenance', 'report_reply', 'follow', 'update')),
    created_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default now(),
    read_at timestamptz,
    scheduled_at timestamptz
);

create index if not exists admin_roles_active_role_idx on public.admin_roles(role) where revoked_at is null;
create unique index if not exists admin_roles_single_owner_idx on public.admin_roles(role) where role = 'owner' and revoked_at is null;
create index if not exists reports_open_created_idx on public.reports(created_at desc) where status in ('open', 'reviewing');
create index if not exists moderation_actions_target_idx on public.moderation_actions(target_user_id, created_at desc);
create index if not exists notifications_recipient_created_idx on public.notifications(recipient_id, created_at desc);

insert into public.admin_permissions(role, permission) values
 ('owner', 'view_dashboard'), ('owner', 'view_users'), ('owner', 'manage_users'),
 ('owner', 'moderate_comments'), ('owner', 'view_reports'), ('owner', 'resolve_reports'),
 ('owner', 'mute_users'), ('owner', 'ban_users'), ('owner', 'manage_roles'),
 ('owner', 'manage_permissions'), ('owner', 'view_audit_logs'), ('owner', 'send_notifications'),
 ('owner', 'manage_shop'), ('owner', 'manage_sources'), ('owner', 'manage_settings'),
 ('admin', 'view_dashboard'), ('admin', 'view_users'), ('admin', 'moderate_comments'),
 ('admin', 'view_reports'), ('admin', 'resolve_reports'), ('admin', 'mute_users'),
 ('moderator', 'view_reports'), ('moderator', 'moderate_comments'), ('moderator', 'mute_users'),
 ('support', 'view_users'), ('support', 'support_accounts')
on conflict (role, permission) do nothing;

-- This fixed ID is the sole owner. No client RPC can promote anyone to owner.
insert into public.admin_roles(user_id, role, granted_by)
values ('47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid, 'owner', null)
on conflict (user_id) do update set role = 'owner', revoked_at = null;

alter table public.admin_roles enable row level security;
alter table public.admin_permissions enable row level security;
alter table public.admin_audit_logs enable row level security;
alter table public.moderation_actions enable row level security;
alter table public.reports enable row level security;
alter table public.notifications enable row level security;

revoke all on public.admin_roles, public.admin_permissions, public.admin_audit_logs,
    public.moderation_actions, public.notifications from anon, authenticated;
grant select on public.admin_roles, public.admin_permissions, public.admin_audit_logs,
    public.moderation_actions to authenticated;
grant select on public.notifications to authenticated;
revoke all on public.reports from anon, authenticated;
grant select on public.reports to authenticated;
grant insert (comment_id, reason, description) on public.reports to authenticated;

create or replace function public.get_my_admin_role()
returns text
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select case when auth.uid() = '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid then 'owner'
        else coalesce((select r.role from public.admin_roles r where r.user_id = auth.uid() and r.revoked_at is null), 'user') end;
$$;

create or replace function public.has_admin_permission(p_permission text)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select auth.uid() = '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid
        or exists (
            select 1 from public.admin_roles r join public.admin_permissions p on p.role = r.role
            where r.user_id = auth.uid() and r.revoked_at is null and p.permission = p_permission
        );
$$;

create or replace function public.is_current_user_restricted(p_user_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_action public.moderation_actions%rowtype;
begin
    if auth.uid() is null or auth.uid() <> p_user_id then return true; end if;
    select * into v_action from public.moderation_actions
      where target_user_id = p_user_id and target_type = 'user' and action in ('mute', 'ban', 'unban')
      order by created_at desc limit 1;
    if not found or v_action.action = 'unban' then return false; end if;
    return v_action.expires_at is null or v_action.expires_at > now();
end;
$$;

create policy admin_roles_select_authorized on public.admin_roles
    for select to authenticated using (user_id = auth.uid() or public.has_admin_permission('manage_roles'));
create policy admin_permissions_select_authenticated on public.admin_permissions
    for select to authenticated using (true);
create policy admin_audit_logs_select_authorized on public.admin_audit_logs
    for select to authenticated using (public.has_admin_permission('view_audit_logs'));
create policy moderation_actions_select_authorized on public.moderation_actions
    for select to authenticated using (public.has_admin_permission('view_reports'));
create policy reports_select_own_or_staff on public.reports
    for select to authenticated using (reporter_id = auth.uid() or public.has_admin_permission('view_reports'));
create policy reports_insert_own on public.reports
    for insert to authenticated with check (reporter_id = auth.uid() and exists (select 1 from public.comments c where c.id = comment_id));
create policy notifications_select_recipient_or_broadcast on public.notifications
    for select to authenticated using (recipient_id is null or recipient_id = auth.uid());

-- Prevent muted/banned accounts from bypassing the UI by inserting comments through REST.
drop policy if exists "insert own comment" on public.comments;
create policy "insert own comment" on public.comments
    for insert to authenticated with check (user_id = auth.uid() and not public.is_current_user_restricted(auth.uid()));

create or replace function public.set_admin_role(p_user_id uuid, p_role text, p_reason text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_actor uuid := auth.uid();
    v_old_role text;
begin
    if v_actor is distinct from '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid then raise exception 'owner only'; end if;
    if p_user_id is null or p_role not in ('admin', 'moderator', 'support', 'user', 'owner') then raise exception 'invalid role'; end if;
    if p_role = 'owner' and p_user_id <> '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid then raise exception 'owner cannot be delegated'; end if;
    if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'reason required'; end if;
    select role into v_old_role from public.admin_roles where user_id = p_user_id and revoked_at is null;
    insert into public.admin_roles(user_id, role, granted_by, revoked_at)
    values (p_user_id, p_role, v_actor, null)
    on conflict (user_id) do update set role = excluded.role, granted_by = excluded.granted_by, created_at = now(), revoked_at = null;
    insert into public.admin_audit_logs(actor_id, action, target_type, target_id, reason, metadata)
    values (v_actor, 'set_role', 'user', p_user_id::text, p_reason, jsonb_build_object('old_role', v_old_role, 'new_role', p_role));
    return true;
end;
$$;

create or replace function public.revoke_admin_role(p_user_id uuid, p_reason text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_actor uuid := auth.uid();
    v_old_role text;
begin
    if v_actor is distinct from '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid then raise exception 'owner only'; end if;
    if p_user_id = '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid then raise exception 'owner role cannot be revoked'; end if;
    if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'reason required'; end if;
    update public.admin_roles set revoked_at = now() where user_id = p_user_id and revoked_at is null returning role into v_old_role;
    if v_old_role is null then return false; end if;
    insert into public.admin_audit_logs(actor_id, action, target_type, target_id, reason, metadata)
    values (v_actor, 'revoke_role', 'user', p_user_id::text, p_reason, jsonb_build_object('old_role', v_old_role));
    return true;
end;
$$;

create or replace function public.set_admin_permission(p_role text, p_permission text, p_enabled boolean, p_reason text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_actor uuid := auth.uid();
begin
    if v_actor is distinct from '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid then raise exception 'owner only'; end if;
    if p_role not in ('admin', 'moderator', 'support', 'user') or length(trim(coalesce(p_permission, ''))) not between 1 and 80 then raise exception 'invalid permission'; end if;
    if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'reason required'; end if;
    if p_enabled then
        insert into public.admin_permissions(role, permission) values (p_role, p_permission) on conflict do nothing;
    else
        delete from public.admin_permissions where role = p_role and permission = p_permission;
    end if;
    insert into public.admin_audit_logs(actor_id, action, target_type, target_id, reason, metadata)
    values (v_actor, 'set_permission', 'role', p_role, p_reason, jsonb_build_object('permission', p_permission, 'enabled', p_enabled));
    return true;
end;
$$;

create or replace function public.create_comment_report(p_comment_id uuid, p_reason text, p_description text default '')
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_user uuid := auth.uid(); v_id uuid;
begin
    if v_user is null then raise exception 'authentication required'; end if;
    if p_reason not in ('abuse', 'inappropriate', 'bullying', 'spam', 'malicious_link', 'spoiler', 'other') then raise exception 'invalid report reason'; end if;
    if not exists (select 1 from public.comments where id = p_comment_id and hidden = false) then raise exception 'comment unavailable'; end if;
    if exists (select 1 from public.reports where comment_id = p_comment_id and reporter_id = v_user) then raise exception 'already reported'; end if;
    insert into public.reports(comment_id, reporter_id, reason, description)
    values (p_comment_id, v_user, p_reason, left(coalesce(p_description, ''), 1000)) returning id into v_id;
    return v_id;
end;
$$;

create or replace function public.moderate_comment(p_comment_id uuid, p_action text, p_reason text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_actor uuid := auth.uid(); v_old_body text;
begin
    if v_actor is null or not public.has_admin_permission('moderate_comments') then raise exception 'not authorized'; end if;
    if p_action not in ('hide', 'delete', 'restore') then raise exception 'invalid action'; end if;
    if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'reason required'; end if;
    select body into v_old_body from public.comments where id = p_comment_id for update;
    if not found then return false; end if;
    if p_action = 'restore' then
        update public.comments set hidden = false where id = p_comment_id;
    elsif p_action = 'delete' then
        update public.comments set hidden = true, body = 'تم حذف هذا التعليق لمخالفته إرشادات المجتمع' where id = p_comment_id;
    else
        update public.comments set hidden = true where id = p_comment_id;
    end if;
    insert into public.admin_audit_logs(actor_id, action, target_type, target_id, reason, metadata)
    values (v_actor, 'comment_' || p_action, 'comment', p_comment_id::text, p_reason, jsonb_build_object('previously_hidden', (p_action <> 'restore')));
    return true;
end;
$$;

create or replace function public.resolve_comment_report(p_report_id uuid, p_status text, p_note text)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_actor uuid := auth.uid(); v_comment_id uuid;
begin
    if v_actor is null or not public.has_admin_permission('resolve_reports') then raise exception 'not authorized'; end if;
    if p_status not in ('reviewing', 'resolved', 'dismissed') then raise exception 'invalid report status'; end if;
    if length(trim(coalesce(p_note, ''))) < 3 then raise exception 'resolution note required'; end if;
    update public.reports set status = p_status, resolved_by = v_actor, resolution_note = left(p_note, 1000), resolved_at = case when p_status in ('resolved','dismissed') then now() else null end, updated_at = now()
      where id = p_report_id returning comment_id into v_comment_id;
    if not found then return false; end if;
    insert into public.admin_audit_logs(actor_id, action, target_type, target_id, reason, metadata)
    values (v_actor, 'report_' || p_status, 'report', p_report_id::text, p_note, jsonb_build_object('comment_id', v_comment_id));
    return true;
end;
$$;

create or replace function public.moderate_user(p_target_user_id uuid, p_action text, p_reason text, p_duration_hours integer default null)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_actor uuid := auth.uid(); v_id uuid; v_expiry timestamptz;
begin
    if v_actor is null or p_target_user_id is null then raise exception 'authentication required'; end if;
    if p_action = 'mute' then
        if not public.has_admin_permission('mute_users') then raise exception 'not authorized'; end if;
        if p_duration_hours is null or p_duration_hours < 1 or p_duration_hours > 168 then raise exception 'mute duration must be 1-168 hours'; end if;
        v_expiry := now() + make_interval(hours => p_duration_hours);
    elsif p_action = 'ban' then
        if not public.has_admin_permission('ban_users') then raise exception 'not authorized'; end if;
        if p_duration_hours is not null and (p_duration_hours < 1 or p_duration_hours > 720) then raise exception 'ban duration must be 1-720 hours'; end if;
        v_expiry := case when p_duration_hours is null then null else now() + make_interval(hours => p_duration_hours) end;
    elsif p_action = 'unban' then
        if not public.has_admin_permission('ban_users') then raise exception 'not authorized'; end if;
        v_expiry := now();
    else raise exception 'invalid action'; end if;
    if p_target_user_id = '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid then raise exception 'owner cannot be moderated'; end if;
    if length(trim(coalesce(p_reason, ''))) < 3 then raise exception 'reason required'; end if;
    insert into public.moderation_actions(moderator_id, target_user_id, target_type, action, reason, expires_at)
    values (v_actor, p_target_user_id, 'user', p_action, p_reason, v_expiry) returning id into v_id;
    insert into public.admin_audit_logs(actor_id, action, target_type, target_id, reason, metadata)
    values (v_actor, 'user_' || p_action, 'user', p_target_user_id::text, p_reason, jsonb_build_object('duration_hours', p_duration_hours, 'expires_at', v_expiry));
    return v_id;
end;
$$;

create or replace function public.send_in_app_notification(p_title text, p_body text, p_type text, p_recipient_id uuid default null)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare v_actor uuid := auth.uid(); v_id uuid;
begin
    if v_actor is null or not public.has_admin_permission('send_notifications') then raise exception 'not authorized'; end if;
    if length(trim(coalesce(p_title, ''))) not between 1 and 120 then raise exception 'invalid title'; end if;
    if length(trim(coalesce(p_body, ''))) not between 1 and 2000 then raise exception 'invalid body'; end if;
    if p_type not in ('announcement', 'system', 'maintenance', 'report_reply', 'follow', 'update') then raise exception 'invalid notification type'; end if;
    insert into public.notifications(recipient_id, title, body, notification_type, created_by)
    values (p_recipient_id, p_title, p_body, p_type, v_actor) returning id into v_id;
    insert into public.admin_audit_logs(actor_id, action, target_type, target_id, metadata)
    values (v_actor, 'send_notification', 'notification', v_id::text, jsonb_build_object('type', p_type, 'recipient_id', p_recipient_id));
    return v_id;
end;
$$;

revoke all on function public.get_my_admin_role() from public, anon;
revoke all on function public.has_admin_permission(text) from public, anon;
revoke all on function public.is_current_user_restricted(uuid) from public, anon;
revoke all on function public.set_admin_role(uuid, text, text) from public, anon, authenticated;
revoke all on function public.revoke_admin_role(uuid, text) from public, anon, authenticated;
revoke all on function public.set_admin_permission(text, text, boolean, text) from public, anon, authenticated;
revoke all on function public.create_comment_report(uuid, text, text) from public, anon;
revoke all on function public.moderate_comment(uuid, text, text) from public, anon;
revoke all on function public.resolve_comment_report(uuid, text, text) from public, anon;
revoke all on function public.moderate_user(uuid, text, text, integer) from public, anon;
revoke all on function public.send_in_app_notification(text, text, text, uuid) from public, anon;
grant execute on function public.get_my_admin_role() to authenticated;
grant execute on function public.has_admin_permission(text) to authenticated;
grant execute on function public.is_current_user_restricted(uuid) to authenticated;
grant execute on function public.create_comment_report(uuid, text, text) to authenticated;
grant execute on function public.set_admin_role(uuid, text, text) to authenticated;
grant execute on function public.revoke_admin_role(uuid, text) to authenticated;
grant execute on function public.set_admin_permission(text, text, boolean, text) to authenticated;
grant execute on function public.moderate_comment(uuid, text, text) to authenticated;
grant execute on function public.resolve_comment_report(uuid, text, text) to authenticated;
grant execute on function public.moderate_user(uuid, text, text, integer) to authenticated;
grant execute on function public.send_in_app_notification(text, text, text, uuid) to authenticated;
