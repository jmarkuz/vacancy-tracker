-- The resume as a PDF of at most two pages (T-097, ADR-050). Every resume version saved from now on keeps its PDF;
-- a package that carries the uploaded resume keeps a PDF made from it when the upload was not a PDF itself.
ALTER TABLE tailored_document
    ADD COLUMN pdf        BYTEA,
    ADD COLUMN pdf_pages  SMALLINT CHECK (pdf_pages BETWEEN 1 AND 2);

ALTER TABLE application_package ADD COLUMN resume_pdf BYTEA;
