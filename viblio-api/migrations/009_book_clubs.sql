BEGIN;
CREATE TABLE IF NOT EXISTS public.viblio_clubs (
    id uuid PRIMARY KEY, name varchar(80) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.viblio_club_members (
    club_id uuid NOT NULL REFERENCES public.viblio_clubs(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    role text NOT NULL CHECK(role IN ('admin','member')), status text NOT NULL CHECK(status IN ('invited','active')),
    joined_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(club_id,user_id)
);
CREATE INDEX IF NOT EXISTS viblio_club_members_user ON public.viblio_club_members(user_id,status);
CREATE TABLE IF NOT EXISTS public.viblio_club_rounds (
    id uuid PRIMARY KEY, club_id uuid NOT NULL REFERENCES public.viblio_clubs(id) ON DELETE CASCADE,
    state text NOT NULL DEFAULT 'voting' CHECK(state IN ('voting','reading','completed')),
    selected_id uuid, due_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS viblio_club_active_round ON public.viblio_club_rounds(club_id) WHERE state<>'completed';
CREATE TABLE IF NOT EXISTS public.viblio_club_proposals (
    id uuid PRIMARY KEY, round_id uuid NOT NULL REFERENCES public.viblio_club_rounds(id) ON DELETE CASCADE,
    proposed_by uuid NOT NULL REFERENCES public.viblio_users(id), book_id varchar(200) NOT NULL,
    title varchar(500) NOT NULL, author varchar(200) NOT NULL, pages integer NOT NULL CHECK(pages BETWEEN 1 AND 100000),
    catalog_id text, cover bytea, UNIQUE(round_id,proposed_by,book_id)
);
CREATE TABLE IF NOT EXISTS public.viblio_club_votes (
    round_id uuid NOT NULL REFERENCES public.viblio_club_rounds(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    proposal_id uuid NOT NULL REFERENCES public.viblio_club_proposals(id) ON DELETE CASCADE,
    PRIMARY KEY(round_id,user_id)
);
CREATE TABLE IF NOT EXISTS public.viblio_club_progress (
    round_id uuid NOT NULL REFERENCES public.viblio_club_rounds(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id) ON DELETE CASCADE,
    page integer NOT NULL CHECK(page>=0), book_id varchar(200), updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(round_id,user_id)
);
CREATE TABLE IF NOT EXISTS public.viblio_club_stages (
    id uuid PRIMARY KEY, round_id uuid NOT NULL REFERENCES public.viblio_club_rounds(id) ON DELETE CASCADE,
    page_limit integer NOT NULL CHECK(page_limit BETWEEN 1 AND 100000), discuss_at timestamptz NOT NULL,
    is_open boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(round_id,page_limit)
);
CREATE TABLE IF NOT EXISTS public.viblio_club_messages (
    id bigserial PRIMARY KEY, stage_id uuid NOT NULL REFERENCES public.viblio_club_stages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.viblio_users(id), body varchar(2000) NOT NULL,
    sent_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS viblio_club_messages_stage ON public.viblio_club_messages(stage_id,id DESC);
COMMIT;
