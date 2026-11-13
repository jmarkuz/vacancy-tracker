-- The date and time of the interview or test-task deadline a mail names, read from the mail text when it is stored.
-- event_at is a wall-clock time without a zone: UTC when event_zone_known, else the time as the mail wrote it
-- (the owner's local time). event_text is the part of the mail it was read from. All NULL for a mail with no event.
ALTER TABLE correspondence ADD COLUMN event_at TIMESTAMP, ADD COLUMN event_zone_known BOOLEAN, ADD COLUMN event_text TEXT;
