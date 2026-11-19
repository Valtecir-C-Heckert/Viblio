BEGIN;
ALTER TABLE public.viblio_club_members
    ADD COLUMN IF NOT EXISTS invited_by uuid REFERENCES public.viblio_users(id) ON DELETE SET NULL;

ALTER TABLE public.viblio_club_messages
    ADD COLUMN IF NOT EXISTS reply_to_id bigint REFERENCES public.viblio_club_messages(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS viblio_club_messages_reply
    ON public.viblio_club_messages(stage_id, reply_to_id)
    WHERE reply_to_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.viblio_club_stage_reads (
    stage_id uuid NOT NULL REFERENCES public.viblio_club_stages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    last_read_message_id bigint NOT NULL DEFAULT 0 CHECK (last_read_message_id >= 0),
    read_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (stage_id, user_id)
);

CREATE INDEX IF NOT EXISTS viblio_club_stage_reads_user
    ON public.viblio_club_stage_reads(user_id, stage_id);
COMMIT;
