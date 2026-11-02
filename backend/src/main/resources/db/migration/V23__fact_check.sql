-- The fact check of a version made from a base draft (T-098, ADR-051): what it states that the base documents do not,
-- and whether the owner confirmed those facts are true before saving. Saving (and so the PDF) waits for that.
ALTER TABLE tailored_document
    ADD COLUMN fact_findings    JSONB,
    ADD COLUMN facts_confirmed  BOOLEAN NOT NULL DEFAULT FALSE;
