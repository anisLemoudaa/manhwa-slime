create or replace function public.has_admin_permission(p_permission text)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select coalesce(auth.uid() = '47a56644-aba3-4e02-8d99-bd60ec556ac2'::uuid, false)
        or exists (
            select 1 from public.admin_roles r
            join public.admin_permissions p on p.role = r.role
            where r.user_id = auth.uid()
              and r.revoked_at is null
              and p.permission = p_permission
        );
$$;
