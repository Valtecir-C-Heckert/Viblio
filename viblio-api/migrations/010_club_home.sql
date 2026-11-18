BEGIN;
ALTER TABLE public.viblio_club_proposals ADD COLUMN IF NOT EXISTS source_user_id uuid REFERENCES public.viblio_users(id);
UPDATE public.viblio_club_proposals SET source_user_id=proposed_by WHERE source_user_id IS NULL;
ALTER TABLE public.viblio_club_proposals DROP CONSTRAINT IF EXISTS viblio_club_proposals_round_id_proposed_by_book_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS viblio_club_source_book ON public.viblio_club_proposals(round_id,source_user_id,book_id) WHERE source_user_id IS NOT NULL;
ALTER TABLE public.viblio_club_rounds ADD COLUMN IF NOT EXISTS started_at timestamptz;
ALTER TABLE public.viblio_club_rounds ADD COLUMN IF NOT EXISTS completed_at timestamptz;
CREATE TABLE IF NOT EXISTS public.viblio_club_events (
    id bigserial PRIMARY KEY,
    round_id uuid NOT NULL REFERENCES public.viblio_club_rounds(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id),
    kind text NOT NULL CHECK(kind IN ('progress','started','schedule','stage','completed')),
    data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS viblio_club_events_round ON public.viblio_club_events(round_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS viblio_activity_book ON public.viblio_activity(user_id,book_id,occurred_at DESC,id DESC);
COMMIT;
