-- How much of a profile's scoring rules the posting could actually be judged by (0-100).
-- A rule whose value the posting does not state, with the "ignore" policy, takes no part in the score (ADR-025), so a
-- posting that matches one stated rule out of four scores 100. Coverage says so: the share of the profile's prefer
-- and avoid weight that was evaluated. NULL on rows scored before this column existed (a recalculation fills it).
ALTER TABLE vacancy_match ADD COLUMN coverage SMALLINT CHECK (coverage BETWEEN 0 AND 100);
-- The coverage of the profile that gave the vacancy its best score, next to match_score.
ALTER TABLE vacancy ADD COLUMN match_coverage SMALLINT CHECK (match_coverage BETWEEN 0 AND 100);
