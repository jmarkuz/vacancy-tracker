-- Collection runs and per-source statistics (T-043, ADR-006, ADR-018).

CREATE TABLE run (
    id           UUID PRIMARY KEY,
    trigger      TEXT        NOT NULL CHECK (trigger IN ('schedule', 'manual')),
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ,
    status       TEXT        NOT NULL CHECK (status IN ('running', 'completed', 'completed_with_errors', 'interrupted'))
);

CREATE TABLE run_source_stat (
    id              UUID PRIMARY KEY,
    run_id          UUID        NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    adapter         TEXT        NOT NULL,
    fetched         INT         NOT NULL DEFAULT 0,
    new_count       INT         NOT NULL DEFAULT 0,
    updated         INT         NOT NULL DEFAULT 0,
    duplicates      INT         NOT NULL DEFAULT 0,
    errors          INT         NOT NULL DEFAULT 0,
    duration_ms     BIGINT      NOT NULL DEFAULT 0,
    error_message   TEXT
);

CREATE INDEX run_source_stat_run_idx ON run_source_stat (run_id);
