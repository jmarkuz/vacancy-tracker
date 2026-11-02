-- Codex review of M6 (2026-11-02).
-- A package that uses the uploaded resume as it is keeps its own copy of that file (or of the pasted text), so
-- replacing or deleting the resume later never changes a prepared or submitted package (ADR-042).
ALTER TABLE application_package
    ADD COLUMN resume_file_name     TEXT,
    ADD COLUMN resume_content_type  TEXT,
    ADD COLUMN resume_bytes         BYTEA;

-- The fit remembers which vacancy text it scored: a changed title or description makes it stale (ADR-040).
ALTER TABLE resume_fit
    ADD COLUMN text_hash TEXT;
