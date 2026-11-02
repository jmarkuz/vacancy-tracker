-- Job-related mail from the owner's mailbox (T-086, T-087, ADR-043): what kind it is, which application it belongs to
-- and the status change it suggests. Only job mail is stored, and only its sender, subject, date and the start of
-- its text. The mail itself stays untouched on the server.
CREATE TABLE correspondence (
    id                UUID        PRIMARY KEY,
    mail_key          TEXT        NOT NULL UNIQUE,
    folder            TEXT,
    sender            TEXT,
    sender_name       TEXT,
    subject           TEXT,
    received_at       TIMESTAMPTZ,
    snippet           TEXT,
    category          TEXT        NOT NULL,
    vacancy_id        UUID        REFERENCES vacancy (id) ON DELETE SET NULL,
    link_reason       TEXT,
    suggested_status  TEXT,
    suggestion_state  TEXT        NOT NULL CHECK (suggestion_state IN ('none', 'pending', 'accepted', 'dismissed')),
    created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX correspondence_received_idx ON correspondence (received_at DESC NULLS LAST);
CREATE INDEX correspondence_vacancy_idx ON correspondence (vacancy_id);
