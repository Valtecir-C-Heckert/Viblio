-- Outside a transaction: keeps reads/writes available during index creation.
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_title_search ON public.valteca USING gin (public.viblio_normalize(titulo) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_author_search ON public.valteca USING gin (public.viblio_normalize(autor) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS viblio_isbn_search ON public.valteca ((regexp_replace(upper(coalesce(isbn,'')), '[^0-9X]', '', 'g')));
