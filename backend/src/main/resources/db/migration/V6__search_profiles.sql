-- Search profiles with criteria and full version history (T-049, ADR-010).

CREATE TABLE search_profile (
    id                 UUID PRIMARY KEY,
    name               TEXT        NOT NULL UNIQUE,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    sources            TEXT[]      NOT NULL DEFAULT '{}',
    thresholds         JSONB       NOT NULL,
    schedule_override  TEXT,
    version            INT         NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The criterion id is the user-visible stable id that matchReasons refer to, so it is unique per profile
-- rather than globally (the ERD sketch showed a UUID).
CREATE TABLE criterion (
    profile_id            UUID        NOT NULL REFERENCES search_profile (id) ON DELETE CASCADE,
    id                    TEXT        NOT NULL,
    position              INT         NOT NULL,
    field                 TEXT        NOT NULL,
    operator              TEXT        NOT NULL,
    value                 JSONB,
    mode                  TEXT        NOT NULL CHECK (mode IN ('require', 'prefer', 'avoid', 'exclude')),
    weight                SMALLINT    NOT NULL CHECK (weight BETWEEN 1 AND 10),
    missing_value_policy  TEXT        NOT NULL DEFAULT 'ignore' CHECK (missing_value_policy IN ('ignore', 'penalize', 'hide')),
    label                 TEXT,
    PRIMARY KEY (profile_id, id)
);

CREATE TABLE search_profile_version (
    profile_id  UUID        NOT NULL REFERENCES search_profile (id) ON DELETE CASCADE,
    version     INT         NOT NULL,
    snapshot    JSONB       NOT NULL,
    saved_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, version)
);
