-- Mutual consent; an invitation to the app never grants access to a private shelf.
BEGIN;
CREATE TABLE IF NOT EXISTS public.viblio_friendships (
    user_low uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    user_high uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    requested_by uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted')),
    created_at timestamptz NOT NULL DEFAULT now(),
    accepted_at timestamptz,
    PRIMARY KEY(user_low,user_high),
    CHECK(user_low<user_high),
    CHECK(requested_by IN (user_low,user_high)),
    CHECK((status='accepted')=(accepted_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS viblio_friendships_high ON public.viblio_friendships(user_high,status);
COMMIT;
