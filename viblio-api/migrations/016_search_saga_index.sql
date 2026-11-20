-- Outside a transaction: keeps reads/writes available during index creation.
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_saga_search ON public.valteca USING gin (public.viblio_normalize(saga) gin_trgm_ops);
