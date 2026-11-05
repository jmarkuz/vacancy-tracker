-- Repairs vacancies read from alert mails whose whole card was one link (LinkedIn): the parser took the title line as
-- the company and "Company · Place" as the location, and the link text as the title. Such a row has a clear
-- signature: title = company || ' ' || location, and the location holds the separator " · ". The title becomes the
-- first line (the old company), the company the part before the separator and the location the part after it.
-- A row the owner edited by hand (title, company or location) is left alone. The search vector is generated, so it
-- follows by itself.
UPDATE vacancy
SET title      = company,
    company    = btrim(split_part(location, ' · ', 1)),
    location   = NULLIF(btrim(substr(location, strpos(location, ' · ') + 3)), ''),
    updated_at = now()
WHERE strpos(location, ' · ') > 1
  AND title = company || ' ' || location
  AND NOT (user_edited_fields && ARRAY['title', 'company', 'location']);
