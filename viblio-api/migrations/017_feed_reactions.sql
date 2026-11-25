-- Reactions on general feed activity cards (only visible to owner and accepted friends).
BEGIN;
CREATE TABLE IF NOT EXISTS public.viblio_activity_reactions (
    activity_id uuid NOT NULL REFERENCES public.viblio_activity(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    reaction varchar(16) NOT NULL CHECK (reaction IN ('clap', 'fire', 'heart', 'book')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, user_id)
);
CREATE INDEX IF NOT EXISTS viblio_activity_reactions_activity
    ON public.viblio_activity_reactions(activity_id);
COMMIT;
