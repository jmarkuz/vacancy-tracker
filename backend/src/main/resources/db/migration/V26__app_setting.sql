-- Small key/value store for settings the owner changes in the UI (first use: how often the mailbox is read).
CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
