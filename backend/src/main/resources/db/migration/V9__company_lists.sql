-- Company allowlist and blocklist, global (profile_id NULL) or per profile (T-059, ADR-028).

CREATE TABLE company_list (
    id          UUID PRIMARY KEY,
    company     TEXT        NOT NULL,
    list_type   TEXT        NOT NULL CHECK (list_type IN ('allowlist', 'blocklist')),
    profile_id  UUID        REFERENCES search_profile (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One entry per company, list and scope. Companies compare without case and extra spaces.
CREATE UNIQUE INDEX company_list_unique ON company_list
    (lower(regexp_replace(btrim(company), '\s+', ' ', 'g')), list_type,
     COALESCE(profile_id, '00000000-0000-0000-0000-000000000000'::uuid));
