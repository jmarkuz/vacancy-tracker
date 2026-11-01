-- Mail that has been read from the alert folder and handled (T-064), so no message is processed twice.
-- mail_key is the Message-ID header, or folder:uidvalidity:uid when a message has none.

CREATE TABLE processed_mail (
    mail_key      TEXT        PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
