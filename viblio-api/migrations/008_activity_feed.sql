-- Explicit sharing consent. Activity is independent of private library snapshots.
BEGIN;
ALTER TABLE public.viblio_users ADD COLUMN IF NOT EXISTS share_activity boolean NOT NULL DEFAULT false;
ALTER TABLE public.viblio_users ADD COLUMN IF NOT EXISTS activity_enabled_at timestamptz;
CREATE TABLE IF NOT EXISTS public.viblio_activity (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    event_key varchar(256) NOT NULL,
    book_id varchar(200) NOT NULL,
    kind text NOT NULL CHECK(kind IN ('read','started','finished','rated')),
    occurred_at timestamptz NOT NULL,
    data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'),
    UNIQUE(user_id,event_key)
);
CREATE INDEX IF NOT EXISTS viblio_activity_owner_time ON public.viblio_activity(user_id,occurred_at DESC,id DESC);
COMMIT;
