-- Score and reasons of one vacancy for one profile (T-052). Replaced on every recalculation.
-- score is NULL when the profile filtered the vacancy out (hidden): a filtered vacancy has no score.
CREATE TABLE vacancy_match (
    vacancy_id       UUID        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    profile_id       UUID        NOT NULL REFERENCES search_profile (id) ON DELETE CASCADE,
    profile_version  INT         NOT NULL,
    score            SMALLINT    CHECK (score BETWEEN 0 AND 100),
    hidden           BOOLEAN     NOT NULL,
    match_reasons    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    matched_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vacancy_id, profile_id),
    CHECK (hidden OR score IS NOT NULL)
);

CREATE INDEX vacancy_match_profile_idx ON vacancy_match (profile_id, score DESC);
