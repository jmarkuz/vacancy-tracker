-- The owner's opt-in to AI (T-082, ADR-041): one row per provider the owner switched on. No row, no AI.
CREATE TABLE ai_consent (
    provider    TEXT        PRIMARY KEY,
    granted_at  TIMESTAMPTZ NOT NULL
);

-- Resumes and cover letters adapted to one vacancy (T-083, ADR-042). A draft is never stored: only what the owner
-- saved after reading and editing it. Every save is a new version; versions are never changed or deleted, so an
-- application package keeps pointing at exactly what was sent.
CREATE TABLE tailored_document (
    id          UUID        PRIMARY KEY,
    vacancy_id  UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    kind        TEXT        NOT NULL CHECK (kind IN ('resume', 'cover_letter')),
    version     INT         NOT NULL CHECK (version > 0),
    content     TEXT        NOT NULL,
    origin      TEXT        NOT NULL CHECK (origin IN ('ai', 'manual')),
    provider    TEXT,
    model       TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (vacancy_id, kind, version)
);
