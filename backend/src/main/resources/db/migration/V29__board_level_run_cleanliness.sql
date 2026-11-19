-- Run cleanliness per company board (ADR-014, ADR-037). Greenhouse, Lever and Ashby read several boards through one
-- adapter. A run in which one board could not be read used to count as unclean for every vacancy of the adapter, so a
-- permanently broken board kept the healthy boards' vacancies from ever expiring.

-- The board (site) of the adapter a vacancy was seen on; NULL for sources without boards and for rows stored before
-- this migration, which are filled in the next time the vacancy is seen.
ALTER TABLE vacancy_source ADD COLUMN board TEXT;

-- The boards of the adapter that could not be read in this run (empty when all could, or when the adapter has no boards).
ALTER TABLE run_source_stat ADD COLUMN failed_boards TEXT[] NOT NULL DEFAULT '{}';
