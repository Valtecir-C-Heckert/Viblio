-- Existing users choose their own name; never invent or change account IDs.
BEGIN;
ALTER TABLE public.viblio_users ADD COLUMN IF NOT EXISTS username text;
CREATE UNIQUE INDEX IF NOT EXISTS viblio_users_username ON public.viblio_users(lower(username)) WHERE username IS NOT NULL;
COMMIT;
