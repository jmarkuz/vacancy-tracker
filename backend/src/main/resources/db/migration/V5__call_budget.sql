-- Call budgets per source and time window, for sources that limit calls per day or hour (T-037, T-039).
CREATE TABLE source_quota (
    adapter     TEXT NOT NULL,
    window_key  TEXT NOT NULL,
    calls       INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (adapter, window_key)
);
