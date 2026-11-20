BEGIN;
CREATE TABLE IF NOT EXISTS public.viblio_wishlist (
    user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    catalog_id text NOT NULL,
    source text NOT NULL DEFAULT '',
    title text NOT NULL,
    author text NOT NULL DEFAULT '',
    genre text NOT NULL DEFAULT '',
    subgenre text NOT NULL DEFAULT '',
    publisher text NOT NULL DEFAULT '',
    saga text NOT NULL DEFAULT '',
    pages integer CHECK (pages IS NULL OR (pages > 0 AND pages <= 100000)),
    sequence integer CHECK (sequence IS NULL OR (sequence > 0 AND sequence <= 10000)),
    cover_url text NOT NULL DEFAULT '',
    isbn text NOT NULL DEFAULT '',
    category text NOT NULL DEFAULT '',
    type text NOT NULL DEFAULT '',
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, catalog_id)
);

CREATE INDEX IF NOT EXISTS viblio_wishlist_user_added
    ON public.viblio_wishlist(user_id, added_at DESC);
COMMIT;
