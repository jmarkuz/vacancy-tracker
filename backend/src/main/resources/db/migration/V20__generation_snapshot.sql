-- What a document version was made from (T-094, ADR-048): the preset's goal, weights and prompt, the guardrail and the
-- base draft, copied at the moment of generation. A version points at its snapshot, so editing or deleting the preset
-- later never changes the history of an application. Rows are only ever inserted.
CREATE TABLE generation_snapshot (
    id              UUID        PRIMARY KEY,
    vacancy_id      UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    kind            TEXT        NOT NULL CHECK (kind IN ('resume', 'cover_letter')),
    preset_id       UUID,
    preset_name     TEXT        NOT NULL,
    guardrail       TEXT        NOT NULL,
    main_goal       TEXT        NOT NULL,
    weights         JSONB       NOT NULL,
    prompt          TEXT        NOT NULL,
    base_draft      TEXT        NOT NULL,
    base_resume     TEXT,
    base_resume_id  UUID,
    created_at      TIMESTAMPTZ NOT NULL
);

ALTER TABLE tailored_document ADD COLUMN snapshot_id UUID REFERENCES generation_snapshot (id);

-- "base": the base draft used as it is.
ALTER TABLE tailored_document DROP CONSTRAINT tailored_document_origin_check;
ALTER TABLE tailored_document ADD CONSTRAINT tailored_document_origin_check CHECK (origin IN ('ai', 'manual', 'base'));
