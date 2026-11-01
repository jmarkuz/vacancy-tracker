-- Baseline schema for M1 core tracker. See docs/architecture.md (ERD) and docs/decisions.md (ADR-004, ADR-015, ADR-021).

CREATE TABLE vacancy (
    id                   UUID PRIMARY KEY,
    title                TEXT        NOT NULL,
    company              TEXT        NOT NULL,
    url                  TEXT,
    canonical_url        TEXT,
    location             TEXT,
    country              TEXT,
    work_mode            TEXT,
    employment_type      TEXT,
    seniority            TEXT,
    salary_min           NUMERIC(14, 2),
    salary_max           NUMERIC(14, 2),
    currency             CHAR(3),
    salary_period        TEXT,
    tags                 TEXT[]      NOT NULL DEFAULT '{}',
    work_language        TEXT,
    visa_sponsorship     BOOLEAN,
    relocation_support   BOOLEAN,
    description          TEXT,
    status               TEXT        NOT NULL DEFAULT 'New',
    priority             SMALLINT    CHECK (priority BETWEEN 1 AND 3),
    match_score          SMALLINT    CHECK (match_score BETWEEN 0 AND 100),
    is_new               BOOLEAN     NOT NULL DEFAULT TRUE,
    posted_at            TIMESTAMPTZ,
    first_seen_at        TIMESTAMPTZ NOT NULL,
    last_seen_at         TIMESTAMPTZ NOT NULL,
    applied_at           TIMESTAMPTZ,
    next_action_at       TIMESTAMPTZ,
    next_action_note     TEXT,
    contact              TEXT,
    notes                TEXT,
    user_edited_fields   TEXT[]      NOT NULL DEFAULT '{}',
    search_vector        TSVECTOR GENERATED ALWAYS AS (
                             to_tsvector('simple',
                                 coalesce(title, '') || ' ' || coalesce(company, '') || ' ' || coalesce(description, ''))
                         ) STORED,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT vacancy_status_chk CHECK (status IN (
        'New', 'Saved', 'Applied', 'Screening', 'Interview', 'Test task', 'Offer', 'Accepted',
        'Rejected', 'Withdrawn', 'Expired', 'Dismissed', 'Hidden'))
);

-- Canonical URL is the first dedup key (ADR-013); a vacancy has at most one canonical URL.
CREATE UNIQUE INDEX vacancy_canonical_url_uk ON vacancy (canonical_url) WHERE canonical_url IS NOT NULL;
CREATE INDEX vacancy_status_idx ON vacancy (status);
CREATE INDEX vacancy_last_seen_idx ON vacancy (last_seen_at);
CREATE INDEX vacancy_search_idx ON vacancy USING GIN (search_vector);

CREATE TABLE vacancy_source (
    id              UUID PRIMARY KEY,
    vacancy_id      UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    adapter         TEXT        NOT NULL,
    external_id     TEXT,
    url             TEXT,
    first_seen_at   TIMESTAMPTZ NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL
);

-- Second dedup key (ADR-013): the same adapter and external ID is one source row.
CREATE UNIQUE INDEX vacancy_source_external_uk ON vacancy_source (adapter, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX vacancy_source_vacancy_idx ON vacancy_source (vacancy_id);

CREATE TABLE status_event (
    id              UUID PRIMARY KEY,
    vacancy_id      UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    from_status     TEXT,
    to_status       TEXT        NOT NULL,
    at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    note            TEXT
);

CREATE INDEX status_event_vacancy_idx ON status_event (vacancy_id, at);

CREATE TABLE saved_view (
    id                UUID PRIMARY KEY,
    name              TEXT        NOT NULL UNIQUE,
    criteria          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    sort              TEXT,
    visible_columns   TEXT[]      NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
