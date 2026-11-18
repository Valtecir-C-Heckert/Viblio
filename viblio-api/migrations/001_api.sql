-- Additive migration for the supplied public.valteca schema. No imported row is rewritten.
BEGIN;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE OR REPLACE FUNCTION public.viblio_normalize(value text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT lower(public.unaccent('public.unaccent', value)) $$;
LOCK TABLE public.valteca IN SHARE ROW EXCLUSIVE MODE;
CREATE SEQUENCE IF NOT EXISTS public.viblio_catalog_id_seq AS bigint;
DO $$
DECLARE existing_default text;
BEGIN
 SELECT column_default INTO existing_default FROM information_schema.columns
 WHERE table_schema='public' AND table_name='valteca' AND column_name='id';
 IF existing_default IS NULL THEN
   PERFORM setval('public.viblio_catalog_id_seq', greatest((SELECT last_value+1 FROM public.viblio_catalog_id_seq), coalesce((SELECT max(id)+1 FROM public.valteca),1)), false);
   ALTER TABLE public.valteca ALTER COLUMN id SET DEFAULT nextval('public.viblio_catalog_id_seq');
 ELSIF existing_default NOT LIKE '%viblio_catalog_id_seq%' THEN
   RAISE EXCEPTION 'valteca.id already has a different default; migration requires review';
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS public.viblio_api_requests (
 request_id uuid PRIMARY KEY, fingerprint text NOT NULL,
 book_id bigint NOT NULL REFERENCES public.valteca(id), uploaded_url text,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.viblio_api_covers (
 id uuid PRIMARY KEY, fingerprint text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
COMMIT;
