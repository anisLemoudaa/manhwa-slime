create or replace function public.get_admin_audit_logs(p_limit integer default 50,p_offset integer default 0)
returns table(id uuid,actor_id uuid,action text,target_type text,target_id text,reason text,created_at timestamptz)
language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
    if auth.uid() is null or not public.has_admin_permission('view_audit_logs') then
        raise exception 'not authorized';
    end if;
    return query
        select l.id,l.actor_id,l.action,l.target_type,l.target_id,l.reason,l.created_at
        from public.admin_audit_logs l
        order by l.created_at desc,l.id desc
        limit least(greatest(coalesce(p_limit,50),1),100)
        offset greatest(coalesce(p_offset,0),0);
end;
$$;
revoke all on function public.get_admin_audit_logs(integer,integer) from public,anon;
grant execute on function public.get_admin_audit_logs(integer,integer) to authenticated;
