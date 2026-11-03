ALTER TABLE public.user_favorites
    DROP CONSTRAINT user_favorites_item_type_check;

ALTER TABLE public.user_favorites
    ADD CONSTRAINT user_favorites_item_type_check
    CHECK (item_type IN ('manga', 'novel', 'community_novel'));
