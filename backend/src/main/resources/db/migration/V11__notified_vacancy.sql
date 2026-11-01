-- Vacancies already sent to a notification channel (T-067), so a vacancy is announced once per channel.

CREATE TABLE notified_vacancy (
    vacancy_id   UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    channel      TEXT        NOT NULL,
    notified_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (vacancy_id, channel)
);
