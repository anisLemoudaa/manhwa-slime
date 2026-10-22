-- Account-wide reading progress must not depend on per-device database row IDs.
-- Keep accepting legacy numeric keys while new clients send a SHA-256 source/manga/chapter key.

alter table public.reading_task_sessions
    drop constraint if exists reading_task_sessions_chapter_key_check;
alter table public.reading_task_sessions
    add constraint reading_task_sessions_chapter_key_check
    check (
        length(chapter_key) between 9 and 100
        and (chapter_key ~ '^manga:[0-9]+:[0-9]+$' or chapter_key ~ '^manga:[0-9a-f]{64}$')
    );

alter table public.reading_task_completions
    drop constraint if exists reading_task_completions_chapter_key_check;
alter table public.reading_task_completions
    add constraint reading_task_completions_chapter_key_check
    check (
        length(chapter_key) between 9 and 100
        and (chapter_key ~ '^manga:[0-9]+:[0-9]+$' or chapter_key ~ '^manga:[0-9a-f]{64}$')
    );

create or replace function public.begin_reading_task_session(p_session_id uuid, p_chapter_key text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user uuid := auth.uid();
    v_existing public.reading_task_sessions%rowtype;
    v_session_id uuid;
    v_required integer;
begin
    if v_user is null then raise exception 'authentication required' using errcode = '42501'; end if;
    if p_session_id is null then raise exception 'session id required'; end if;
    if length(coalesce(p_chapter_key, '')) not between 9 and 100
       or not (p_chapter_key ~ '^manga:[0-9]+:[0-9]+$' or p_chapter_key ~ '^manga:[0-9a-f]{64}$') then
        raise exception 'invalid canonical chapter key';
    end if;

    select coalesce(greatest(max(d.min_elapsed_seconds), 20), 20)
      into v_required
      from public.reading_task_definitions d
     where d.active
       and (d.starts_at is null or d.starts_at <= now())
       and (d.ends_at is null or d.ends_at > now());

    update public.reading_task_sessions
       set status = 'rejected', completed_at = now()
     where user_id = v_user and status = 'open' and expires_at <= now();

    select * into v_existing
      from public.reading_task_sessions
     where id = p_session_id
     for update;
    if found then
        if v_existing.user_id <> v_user or v_existing.chapter_key <> p_chapter_key then
            raise exception 'session belongs to another user or chapter' using errcode = '42501';
        end if;
        return jsonb_build_object('session_id', v_existing.id, 'started_at', v_existing.started_at,
                                  'status', v_existing.status);
    end if;

    -- A new reader lifecycle gets a fresh server start time; it cannot reuse a stale open session.
    update public.reading_task_sessions
       set status = 'rejected', completed_at = now()
     where user_id = v_user and chapter_key = p_chapter_key and status = 'open';

    insert into public.reading_task_sessions(id, user_id, chapter_key, required_elapsed_seconds)
    values (p_session_id, v_user, p_chapter_key, v_required)
    on conflict do nothing
    returning id into v_session_id;
    if v_session_id is null then
        select id into v_session_id
          from public.reading_task_sessions
         where user_id = v_user and chapter_key = p_chapter_key and status = 'open'
         order by started_at desc limit 1;
    end if;
    if v_session_id is null then raise exception 'could not start reading session'; end if;
    return jsonb_build_object('session_id', v_session_id, 'started_at', now(), 'status', 'open');
end;
$$;

-- CREATE OR REPLACE retains the function ACL, but state intended grants explicitly.
revoke all on function public.begin_reading_task_session(uuid, text) from public, anon;
grant execute on function public.begin_reading_task_session(uuid, text) to authenticated;
