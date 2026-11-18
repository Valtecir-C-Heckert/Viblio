-- Independent identities and private libraries; never migrates legacy Valteca users.
BEGIN;
CREATE TABLE IF NOT EXISTS public.viblio_users(id uuid PRIMARY KEY,email text NOT NULL UNIQUE,password_hash text NOT NULL,is_admin boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS public.viblio_sessions(token_hash text PRIMARY KEY,user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,expires_at timestamptz NOT NULL DEFAULT now()+interval '30 days');
CREATE INDEX IF NOT EXISTS viblio_sessions_user ON public.viblio_sessions(user_id);
CREATE TABLE IF NOT EXISTS public.viblio_invites(id uuid PRIMARY KEY,token_hash text NOT NULL UNIQUE,created_by uuid NOT NULL REFERENCES public.viblio_users(id),expires_at timestamptz NOT NULL,revoked boolean NOT NULL DEFAULT false,used_by uuid REFERENCES public.viblio_users(id),used_at timestamptz,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS public.viblio_recovery(token_hash text PRIMARY KEY,user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,expires_at timestamptz NOT NULL DEFAULT now()+interval '1 day');
CREATE TABLE IF NOT EXISTS public.viblio_auth_attempts(bucket text PRIMARY KEY,started_at timestamptz NOT NULL DEFAULT now(),attempts integer NOT NULL);
CREATE TABLE IF NOT EXISTS public.viblio_library_versions(user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,revision bigint NOT NULL,request_id uuid NOT NULL,request_hash text NOT NULL,digest text NOT NULL,filename text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(user_id,revision),UNIQUE(user_id,request_id));
CREATE TABLE IF NOT EXISTS public.viblio_library_bases(user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,revision bigint NOT NULL,snapshot jsonb NOT NULL,PRIMARY KEY(user_id,revision));
COMMIT;
