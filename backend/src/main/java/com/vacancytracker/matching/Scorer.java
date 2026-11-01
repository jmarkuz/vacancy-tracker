package com.vacancytracker.matching;

import com.vacancytracker.matching.RuleEngine.CriterionResult;
import com.vacancytracker.matching.RuleEngine.Effect;
import com.vacancytracker.matching.RuleEngine.RuleResult;
import com.vacancytracker.matching.RuleEngine.Verdict;
import java.util.ArrayList;
import java.util.List;

/**
 * The score of a vacancy for a profile (T-052, docs/search-profiles.md section 3, ADR-025).
 *
 * positive = weights of satisfied prefer criteria; negative = weights of satisfied avoid criteria plus
 * weights of penalized missing values; maxPositive = weights of all prefer criteria; absorb = weights of all
 * avoid criteria. A criterion skipped by "ignore" because its value is missing takes no part at all.
 * score = clamp(round(100 * (positive - negative + bonus + absorb) / (maxPositive + absorb + bonus)), 0, 100).
 * A filtered vacancy has no score. Adding a satisfied prefer criterion never lowers the score.
 */
public final class Scorer {

    /** score is null when the vacancy is filtered out; reasons explain every change and every filter. */
    public record ScoreOutcome(Integer score, boolean hidden, List<MatchReason> reasons) {
    }

    private Scorer() {
    }

    /** criteria and result.results() are in the same order. allowlistBonus is 0 unless the company is allowlisted. */
    public static ScoreOutcome score(List<Criterion> criteria, RuleResult result, int allowlistBonus) {
        int positive = 0;
        int negative = 0;
        int maxPositive = 0;
        int absorb = 0;
        List<MatchReason> reasons = new ArrayList<>();

        for (int i = 0; i < criteria.size(); i++) {
            Criterion c = criteria.get(i);
            CriterionResult r = result.results().get(i);
            int w = r.weight();
            boolean skipped = r.verdict() == Verdict.MISSING && r.effect() == Effect.NONE;
            if (!skipped) {
                if ("prefer".equals(c.mode())) {
                    maxPositive += w;
                } else if ("avoid".equals(c.mode())) {
                    absorb += w;
                }
            }
            switch (r.effect()) {
                case BONUS -> {
                    positive += w;
                    reasons.add(new MatchReason(c.id(), w, "prefer", "+" + w + ": " + ReasonText.describe(c)));
                }
                case PENALTY -> {
                    negative += w;
                    boolean missing = r.verdict() == Verdict.MISSING;
                    reasons.add(new MatchReason(c.id(), -w, missing ? "missing" : "avoid",
                            "-" + w + ": " + ReasonText.describe(c) + (missing ? " (not stated)" : "")));
                }
                case FILTER_OUT -> reasons.add(filterReason(c, r));
                default -> { }
            }
        }

        if (result.hidden()) {
            return new ScoreOutcome(null, true, List.copyOf(reasons));
        }
        int denominator = maxPositive + absorb + allowlistBonus;
        if (denominator == 0) {
            return new ScoreOutcome(0, false, List.copyOf(reasons));
        }
        int numerator = Math.max(0, positive - negative + allowlistBonus + absorb);
        int score = Math.min(100, (200 * numerator + denominator) / (2 * denominator));
        return new ScoreOutcome(score, false, List.copyOf(reasons));
    }

    private static MatchReason filterReason(Criterion c, CriterionResult r) {
        String what = ReasonText.describe(c);
        if (r.verdict() == Verdict.MISSING) {
            return new MatchReason(c.id(), 0, "missing", "Filtered out: " + what + " is not stated");
        }
        if ("exclude".equals(c.mode())) {
            return new MatchReason(c.id(), 0, "exclude", "Filtered out: " + what);
        }
        return new MatchReason(c.id(), 0, "require", "Filtered out: required " + what + " is not met");
    }
}
