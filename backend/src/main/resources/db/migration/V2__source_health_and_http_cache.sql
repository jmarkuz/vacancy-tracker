-- Source health for the circuit breaker (ADR-018) and conditional-request cache (ETag, Last-Modified).

CREATE TABLE source_state (
    adapter               TEXT PRIMARY KEY,
    enabled               BOOLEAN     NOT NULL DEFAULT TRUE,
    last_success_at       TIMESTAMPTZ,
    last_failure_at       TIMESTAMPTZ,
    consecutive_failures  INT         NOT NULL DEFAULT 0,
    open_until            TIMESTAMPTZ,
    last_error            TEXT
);

CREATE TABLE http_cache (
    url             TEXT PRIMARY KEY,
    etag            TEXT,
    last_modified   TEXT,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
