-- An application package (T-084, T-085, ADR-042): the document versions chosen for one application, the page where
-- the owner submits it, and whether the owner has said it was submitted. The app never submits anything itself.
CREATE TABLE application_package (
    id                        UUID        PRIMARY KEY,
    vacancy_id                UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    resume_document_id        UUID        REFERENCES tailored_document (id),
    cover_letter_document_id  UUID        REFERENCES tailored_document (id),
    apply_url                 TEXT,
    note                      TEXT,
    status                    TEXT        NOT NULL CHECK (status IN ('prepared', 'submitted')),
    created_at                TIMESTAMPTZ NOT NULL,
    submitted_at              TIMESTAMPTZ
);

CREATE INDEX application_package_vacancy_idx ON application_package (vacancy_id, created_at DESC);
