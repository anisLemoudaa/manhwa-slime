-- Read-only assertions for the deployed discovery/privacy RPCs.
-- Run with a privileged SQL test connection; this script does not mutate rows.
do $$
declare
    v_count integer;
    v_leaks integer;
    v_hidden integer;
    v_rejected boolean;
    v_payload jsonb;
begin
    assert has_function_privilege('anon', 'public.search_public_users(text,integer,integer)', 'execute'), 'anon search grant';
    assert has_function_privilege('authenticated', 'public.search_public_users(text,integer,integer)', 'execute'), 'authenticated search grant';
    assert not has_function_privilege('anon', 'public.get_my_privacy_settings()', 'execute'), 'privacy read must require auth';
    assert not has_function_privilege('anon', 'public.update_my_privacy_settings(jsonb)', 'execute'), 'privacy write must require auth';
    assert has_function_privilege('authenticated', 'public.get_my_privacy_settings()', 'execute'), 'authenticated privacy read';
    assert has_function_privilege('authenticated', 'public.update_my_privacy_settings(jsonb)', 'execute'), 'authenticated privacy write';
    assert not has_column_privilege('authenticated', 'public.user_profiles', 'email_visibility', 'UPDATE'), 'privacy column direct update forbidden';
    assert not has_column_privilege('authenticated', 'public.user_profiles', 'profile_visibility', 'UPDATE'), 'profile visibility direct update forbidden';
    assert has_function_privilege('anon', 'public.get_public_profile_v2(uuid)', 'execute'), 'public profile read grant';
    assert has_function_privilege('anon', 'public.get_public_favorites(uuid,text,integer,integer)', 'execute'), 'public favorites RPC grant';
    assert has_function_privilege('anon', 'public.get_public_reading_stats(uuid)', 'execute'), 'public stats RPC grant';
    assert has_function_privilege('anon', 'public.get_public_user_comments(uuid,integer,integer)', 'execute'), 'public comments RPC grant';

    select count(*) into v_count from public.search_public_users('قارئ', 200, 0);
    assert v_count <= 20, 'search page size must cap at 20';
    select count(*) into v_leaks
      from public.search_public_users('قارئ', 200, 0) s
      left join public.user_profiles p on p.user_id=s.user_id
      where s.email is not null and coalesce(p.email_visibility,'private') <> 'public';
    assert v_leaks = 0, 'private email must never be returned';
    select count(*) into v_hidden
      from public.search_public_users('قارئ', 200, 0) s
      join public.user_profiles p on p.user_id=s.user_id
      where p.profile_visibility='private' and s.user_id is distinct from auth.uid();
    assert v_hidden = 0, 'private profiles must not be discoverable to other viewers';

    v_rejected := false;
    begin
        perform * from public.search_public_users('', 20, 0);
    exception when sqlstate 'P0001' then
        v_rejected := true;
    end;
    assert v_rejected, 'empty search must reject';

    v_rejected := false;
    begin
        perform public.get_public_favorites(auth.uid(), 'invalid', 20, 0);
    exception when sqlstate 'P0001' then
        v_rejected := true;
    end;
    assert v_rejected, 'invalid favorite type must reject';

    v_payload := public.get_public_favorites(auth.uid(), 'manga', 999, 0);
    assert jsonb_typeof(v_payload->'items') = 'array', 'favorite payload must be an array';
    assert jsonb_array_length(v_payload->'items') <= 50, 'favorites page size must cap at 50';
    v_payload := public.get_public_reading_stats(auth.uid());
    assert jsonb_typeof(v_payload) = 'object', 'statistics payload must be an object';
    assert not (v_payload ? 'email'), 'statistics must not contain email';

    select count(*) into v_count from public.get_public_user_comments(auth.uid(), 999, 0);
    assert v_count <= 50, 'comments page size must cap at 50';
end;
$$;
