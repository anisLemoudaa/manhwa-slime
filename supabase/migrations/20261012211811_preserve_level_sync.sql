-- Level remains synced by the current read-count profile flow for compatibility.
-- Rank and rank_xp remain server-owned and cannot be written through REST.
revoke update on public.user_profiles from authenticated;
revoke update (display_name, avatar_data, cover_data, level, rank, rank_xp, equipped_frame_id, updated_at)
    on public.user_profiles from authenticated;
grant update (display_name, avatar_data, cover_data, level, updated_at)
    on public.user_profiles to authenticated;

revoke insert on public.user_profiles from authenticated;
revoke insert (user_id, display_name, avatar_data, cover_data, level, rank, rank_xp, equipped_frame_id, updated_at)
    on public.user_profiles from authenticated;
grant insert (user_id, display_name, avatar_data, cover_data, level, updated_at)
    on public.user_profiles to authenticated;
