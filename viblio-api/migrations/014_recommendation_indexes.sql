-- Outside a transaction: keeps reads/writes available during index creation.
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_genre_search ON public.valteca USING gin (public.viblio_normalize(genero) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_subgenre_search ON public.valteca USING gin (public.viblio_normalize(subgenero) gin_trgm_ops);
