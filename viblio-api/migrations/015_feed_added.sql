-- Allow 'added' events in public.viblio_activity so friends see newly added shelf books.
BEGIN;
DO $$
DECLARE
    cname text;
BEGIN
    FOR cname IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'public.viblio_activity'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%kind%'
    LOOP
        EXECUTE format('ALTER TABLE public.viblio_activity DROP CONSTRAINT %I', cname);
    END LOOP;
    ALTER TABLE public.viblio_activity
        ADD CONSTRAINT viblio_activity_kind_check
        CHECK (kind IN ('read', 'started', 'finished', 'rated', 'added'));
END $$;
COMMIT;
