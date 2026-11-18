CREATE TABLE public.valteca (
 id bigint PRIMARY KEY,titulo text NOT NULL,autor text,genero text,subgenero text,
 metricas_totais integer,saga text,editora text,capa_url text,categoria text,
 created_at timestamptz DEFAULT now(),metrica_tipo text,extra_data jsonb,ordem numeric,
 informacao text,isbn text,data_lancamento text,demografia text,tipo text,sinopse text,
 idioma text,enriched boolean DEFAULT false,work_key text,edition_key text
);
