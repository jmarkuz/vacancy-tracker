-- Vacancy analysis with AI (T-095, ADR-049). Each row remembers the hash of the text it was computed from, so a changed
-- vacancy or document is recognised and nothing stale is shown as current.

-- One requirement checklist per vacancy (must-have, important, nice-to-have), by the hash of title and description.
CREATE TABLE vacancy_checklist (
    vacancy_id      UUID        PRIMARY KEY REFERENCES vacancy (id) ON DELETE CASCADE,
    text_hash       TEXT        NOT NULL,
    checklist_hash  TEXT        NOT NULL,
    checklist       JSONB       NOT NULL,
    provider        TEXT,
    model           TEXT,
    created_at      TIMESTAMPTZ NOT NULL
);

-- How one document text covers one checklist: the statuses after the server checked every quote.
CREATE TABLE document_match (
    vacancy_id      UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    checklist_hash  TEXT        NOT NULL,
    text_hash       TEXT        NOT NULL,
    items           JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vacancy_id, checklist_hash, text_hash)
);

-- How the vacancy fits one goal (main goal and weights, by their hash): a 0..100 rating per parameter.
CREATE TABLE goal_match (
    vacancy_id   UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    goal_hash    TEXT        NOT NULL,
    text_hash    TEXT        NOT NULL,
    result       JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vacancy_id, goal_hash)
);
