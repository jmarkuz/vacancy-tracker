-- A run may be limited to one source ("Refresh this source", T-047). NULL means all sources.
ALTER TABLE run ADD COLUMN scope TEXT;
