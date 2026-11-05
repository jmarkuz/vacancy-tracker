-- The vacancies listed in one digest mail (a LinkedIn job alert with several postings), read from the mail when it is
-- stored: [{"title":..., "company":..., "location":..., "url":...}]. NULL for every other mail.
ALTER TABLE correspondence ADD COLUMN jobs JSONB;
