-- Additive gallery: never rewrites valteca.capa_url or imported book metadata.
BEGIN;
CREATE TABLE IF NOT EXISTS public.viblio_book_covers (
 book_id bigint NOT NULL REFERENCES public.valteca(id) ON DELETE CASCADE,
 cover_id uuid NOT NULL REFERENCES public.viblio_api_covers(id),
 fingerprint text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(book_id,cover_id),
 UNIQUE(book_id,fingerprint)
);
-- Recover covers already uploaded by APKs 0.1.4–0.1.7; no image download is needed.
INSERT INTO public.viblio_book_covers(book_id,cover_id,fingerprint,created_at)
SELECT DISTINCT ON (r.book_id,c.fingerprint) r.book_id,c.id,c.fingerprint,r.created_at
FROM public.viblio_api_requests r JOIN public.viblio_api_covers c ON c.id=r.request_id
WHERE r.uploaded_url IS NOT NULL
ORDER BY r.book_id,c.fingerprint,r.created_at,r.request_id
ON CONFLICT DO NOTHING;
COMMIT;
