-- Fuzzy de-duplication filters on lower(company) (Deduplicator, VacancyRepository); without an index every posting scans the table.
CREATE INDEX IF NOT EXISTS idx_vacancy_company_lower ON vacancy (lower(company));
