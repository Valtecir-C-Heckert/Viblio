-- Hashes bound index size for long imported text. SQL also checks exact normalized values.
-- Read-only grouping indexes: keep every imported edition and cover unchanged.
-- Run outside a transaction, like migration 002.
CREATE OR REPLACE FUNCTION public.viblio_cover_key(value text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
 SELECT btrim(regexp_replace(public.viblio_normalize(coalesce(value,'')), '\s+', ' ', 'g'))
$$;
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_cover_title_author ON public.valteca (md5(public.viblio_cover_key(titulo)),md5(public.viblio_cover_key(autor)));
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_cover_work ON public.valteca (md5(nullif(btrim(work_key),'')));
