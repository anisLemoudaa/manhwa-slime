-- Keep profile sync fields writable while reserving server-owned display/admin fields.
-- In particular, a first-time profile insert must not assign an unowned frame or fake follower count.
revoke insert on public.user_profiles from authenticated;
grant insert (user_id, display_name, avatar_data, cover_data, level, rank, updated_at)
    on public.user_profiles to authenticated;
