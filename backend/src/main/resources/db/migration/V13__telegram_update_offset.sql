-- Where the Telegram forward source (T-089, ADR-045) continues reading the owner's bot: the next getUpdates offset.
-- Written only after the vacancies of a batch are stored, so a failed run reads the same messages again.

CREATE TABLE telegram_update_offset (
    source       TEXT   PRIMARY KEY,
    next_offset  BIGINT NOT NULL
);
