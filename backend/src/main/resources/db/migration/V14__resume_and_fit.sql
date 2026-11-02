-- The user's resume (T-080) and how well each vacancy fits it (T-081). See docs/decisions.md ADR-040.
-- One resume is active at a time; revision grows with every change of its text or keywords, so a fit computed
-- for an older revision is known to be stale.

CREATE TABLE resume (
    id            UUID PRIMARY KEY,
    name          TEXT        NOT NULL,
    file_name     TEXT,
    content_type  TEXT,
    original      BYTEA,
    text          TEXT        NOT NULL,
    keywords      TEXT[]      NOT NULL DEFAULT '{}',
    revision      INT         NOT NULL DEFAULT 1,
    active        BOOLEAN     NOT NULL DEFAULT FALSE,
    uploaded_at   TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX resume_one_active_uk ON resume (active) WHERE active;

-- Replaced on every recalculation, like vacancy_match: there is no history of fits.
CREATE TABLE resume_fit (
    vacancy_id       UUID        PRIMARY KEY REFERENCES vacancy (id) ON DELETE CASCADE,
    resume_id        UUID        NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    resume_revision  INT         NOT NULL,
    score            SMALLINT    NOT NULL CHECK (score BETWEEN 0 AND 100),
    matched          TEXT[]      NOT NULL DEFAULT '{}',
    missing          TEXT[]      NOT NULL DEFAULT '{}',
    reasons          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    computed_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX resume_fit_score_idx ON resume_fit (score DESC);
