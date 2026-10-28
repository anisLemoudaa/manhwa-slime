-- Add a public author byline while preserving all existing community novel rows.
ALTER TABLE public.community_novels
    ADD COLUMN IF NOT EXISTS author_name text NOT NULL DEFAULT '';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'community_novels_author_name_length'
          AND conrelid = 'public.community_novels'::regclass
    ) THEN
        ALTER TABLE public.community_novels
            ADD CONSTRAINT community_novels_author_name_length
            CHECK (char_length(trim(author_name)) <= 80);
    END IF;
END;
$$;

-- This RPC must run with its owner's privileges because ordinary users cannot update
-- another author's novel row under RLS. It is still callable only by authenticated users
-- and only increments published novels.
CREATE OR REPLACE FUNCTION public.community_increment_view(p_novel_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501';
    END IF;

    UPDATE public.community_novels
    SET view_count = view_count + 1
    WHERE id = p_novel_id
      AND status = 'published';
END;
$$;

REVOKE ALL ON FUNCTION public.community_increment_view(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.community_increment_view(uuid) TO authenticated;
