package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.Scorer.ScoreOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ScorerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleEngine engine = new RuleEngine(new YearlyConverter());

    private Criterion c(String id, String field, String op, String value, String mode, int weight, String policy, String label) {
        try {
            return new Criterion(id, field, op, value == null ? null : mapper.readTree(value), mode, weight, policy, label);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** A prefer or avoid criterion on the title containing one token. */
    private Criterion token(String id, String token, String mode, int weight) {
        return c(id, "title", "contains_any", "[\"" + token + "\"]", mode, weight, null, null);
    }

    private ScoreOutcome score(List<Criterion> criteria, Map<String, Object> facts) {
        return Scorer.score(criteria, engine.evaluate(criteria, facts), 0);
    }

    // ---- the formula, by hand ----

    @Test
    void preferOnlyScoresTheShareOfWeightEarned() {
        List<Criterion> criteria = List.of(token("a", "java", "prefer", 8), token("b", "remote", "prefer", 2));

        assertThat(score(criteria, Map.of("title", "java remote")).score()).isEqualTo(100);
        assertThat(score(criteria, Map.of("title", "java")).score()).isEqualTo(80);
        assertThat(score(criteria, Map.of("title", "remote")).score()).isEqualTo(20);
        assertThat(score(criteria, Map.of("title", "cobol")).score()).isZero();
    }

    @Test
    void avoidCriteriaRaiseTheBaselineAndASatisfiedOneLowersTheScore() {
        List<Criterion> criteria = List.of(token("p", "java", "prefer", 6), token("n", "intern", "avoid", 4));

        assertThat(score(criteria, Map.of("title", "java")).score()).isEqualTo(100);            // (6+4)/10
        assertThat(score(criteria, Map.of("title", "java intern")).score()).isEqualTo(60);      // (6-4+4)/10
        assertThat(score(criteria, Map.of("title", "cobol")).score()).isEqualTo(40);            // 4/10
        assertThat(score(criteria, Map.of("title", "cobol intern")).score()).isZero();          // (0-4+4)/10
    }

    @Test
    void scoresAreRounded() {
        List<Criterion> three = List.of(token("a", "x", "prefer", 1), token("b", "y", "prefer", 1), token("c", "z", "prefer", 1));

        assertThat(score(three, Map.of("title", "x")).score()).isEqualTo(33);
        assertThat(score(three, Map.of("title", "x y")).score()).isEqualTo(67);
    }

    @Test
    void anIgnoredMissingCriterionTakesNoPart() {
        Criterion keyword = token("kw", "java", "prefer", 5);
        Criterion salary = c("sal", "salary", "gte", "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}",
                "prefer", 5, "ignore", null);

        ScoreOutcome outcome = score(List.of(keyword, salary), Map.of("title", "java"));

        assertThat(outcome.score()).isEqualTo(100);
        assertThat(outcome.reasons()).extracting(MatchReason::criterionId).containsExactly("kw");
    }

    @Test
    void aPenalizedMissingValueCountsAgainstTheVacancy() {
        Criterion keyword = token("kw", "java", "prefer", 5);
        Criterion salary = c("sal", "salary", "gte", "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}",
                "prefer", 5, "penalize", "Salary at least 50k");

        ScoreOutcome outcome = score(List.of(keyword, salary), Map.of("title", "java"));

        assertThat(outcome.score()).isZero();   // (5 - 5) / 10
        assertThat(outcome.reasons()).extracting(MatchReason::delta).containsExactly(5, -5);
        assertThat(outcome.reasons().get(1).kind()).isEqualTo("missing");
        assertThat(outcome.reasons().get(1).text()).isEqualTo("-5: Salary at least 50k (not stated)");
    }

    @Test
    void theScoreNeverGoesBelowZeroOrAboveOneHundred() {
        List<Criterion> criteria = List.of(token("p", "java", "prefer", 1),
                c("m1", "city", "equals", "\"Berlin\"", "require", 10, "penalize", null),
                c("m2", "industry", "equals", "\"Tech\"", "require", 10, "penalize", null));

        assertThat(score(criteria, Map.of("title", "java")).score()).isZero();
        assertThat(score(List.of(token("p", "java", "prefer", 1)), Map.of("title", "java")).score()).isEqualTo(100);
    }

    @Test
    void anAllowlistBonusRaisesTheScore() {
        List<Criterion> criteria = List.of(token("p", "java", "prefer", 10));
        var result = engine.evaluate(criteria, Map.of("title", "cobol"));

        assertThat(Scorer.score(criteria, result, 0).score()).isZero();
        assertThat(Scorer.score(criteria, result, 10).score()).isEqualTo(50);   // (0+10)/(10+10)
    }

    // ---- filtering and the no-score cases ----

    @Test
    void aFilteredVacancyHasNoScoreAndTheReasonSaysWhy() {
        Criterion must = c("must", "country", "in", "[\"DE\"]", "require", 5, null, "Allowed country");
        Criterion ban = token("ban", "intern", "exclude", 3);

        ScoreOutcome unmet = score(List.of(must), Map.of("country", "PL"));
        ScoreOutcome excluded = score(List.of(ban), Map.of("title", "Java Intern"));

        assertThat(unmet.hidden()).isTrue();
        assertThat(unmet.score()).isNull();
        assertThat(unmet.reasons()).containsExactly(
                new MatchReason("must", 0, "require", "Filtered out: required Allowed country is not met"));
        assertThat(excluded.hidden()).isTrue();
        assertThat(excluded.reasons().get(0).kind()).isEqualTo("exclude");
    }

    @Test
    void aHiddenByMissingValueReasonIsExplained() {
        Criterion must = c("must", "country", "in", "[\"DE\"]", "require", 5, "hide", "Allowed country");

        ScoreOutcome outcome = score(List.of(must), Map.of());

        assertThat(outcome.hidden()).isTrue();
        assertThat(outcome.reasons().get(0).text()).isEqualTo("Filtered out: Allowed country is not stated");
    }

    @Test
    void withNoScoringCriteriaTheScoreIsZeroWithoutReasons() {
        Criterion must = c("must", "country", "in", "[\"DE\"]", "require", 5, null, null);

        ScoreOutcome outcome = score(List.of(must), Map.of("country", "DE"));

        assertThat(outcome.hidden()).isFalse();
        assertThat(outcome.score()).isZero();
        assertThat(outcome.reasons()).isEmpty();
        assertThat(score(List.of(), Map.of()).score()).isZero();
    }

    // ---- reasons ----

    @Test
    void reasonsUseTheLabelOrASentenceAndReferenceTheCriterionId() {
        Criterion labelled = c("kw", "description", "contains_any", "[\"spring\"]", "prefer", 8, null, "Keyword in description");
        Criterion plain = c("loc", "country", "in", "[\"DE\",\"PL\"]", "prefer", 4, null, null);
        Criterion bad = c("bad", "title", "contains_any", "[\"intern\"]", "avoid", 3, null, "Undesired role");
        Criterion pay = c("pay", "salary", "gte", "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}",
                "prefer", 2, null, null);
        Map<String, Object> facts = new HashMap<>();
        facts.put("description", "We use Spring");
        facts.put("country", "DE");
        facts.put("title", "Intern");
        facts.put("salary", new RuleEngine.SalaryFact(new java.math.BigDecimal("60000"), null, "EUR", "year"));

        ScoreOutcome outcome = score(List.of(labelled, plain, bad, pay), facts);

        assertThat(outcome.reasons()).containsExactly(
                new MatchReason("kw", 8, "prefer", "+8: Keyword in description"),
                new MatchReason("loc", 4, "prefer", "+4: country is one of DE, PL"),
                new MatchReason("bad", -3, "avoid", "-3: Undesired role"),
                new MatchReason("pay", 2, "prefer", "+2: salary is at least 50000 EUR per year"));
    }

    // ---- properties ----

    private static final String[] TOKENS = {"java", "kotlin", "remote", "senior", "cloud", "data", "ops", "qa", "go", "rust"};

    private record Case(List<Criterion> criteria, Map<String, Object> facts, int score) {
    }

    private Case randomCase(Random random) {
        int n = random.nextInt(6);
        List<Criterion> criteria = new ArrayList<>();
        List<String> present = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String token = TOKENS[i];
            String mode = random.nextBoolean() ? "prefer" : "avoid";
            criteria.add(token("c" + i, token, mode, 1 + random.nextInt(10)));
            if (random.nextBoolean()) {
                present.add(token);
            }
        }
        Map<String, Object> facts = Map.of("title", String.join(" ", present) + " base");
        return new Case(criteria, facts, score(criteria, facts).score());
    }

    @Test
    void theScoreIsAlwaysBetweenZeroAndOneHundred() {
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            Case c = randomCase(random);
            assertThat(c.score()).isBetween(0, 100);
        }
    }

    @Test
    void addingASatisfiedPreferCriterionNeverLowersTheScore() {
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            Case before = randomCase(random);
            List<Criterion> more = new ArrayList<>(before.criteria());
            String extra = TOKENS[6 + random.nextInt(4)];
            more.add(token("extra", extra, "prefer", 1 + random.nextInt(10)));
            Map<String, Object> facts = Map.of("title", before.facts().get("title") + " " + extra);

            int base = score(before.criteria(), facts).score();
            int after = score(more, facts).score();

            assertThat(after).as("case %d", i).isGreaterThanOrEqualTo(base);
        }
    }

    @Test
    void addingAnUnsatisfiedPreferCriterionNeverRaisesTheScore() {
        Random random = new Random(13);
        for (int i = 0; i < 2000; i++) {
            Case before = randomCase(random);
            List<Criterion> more = new ArrayList<>(before.criteria());
            more.add(token("extra", "zzzunmatched", "prefer", 1 + random.nextInt(10)));

            assertThat(score(more, before.facts()).score()).as("case %d", i).isLessThanOrEqualTo(before.score());
        }
    }

    @Test
    void addingASatisfiedAvoidCriterionNeverRaisesTheScore() {
        Random random = new Random(17);
        for (int i = 0; i < 2000; i++) {
            Case before = randomCase(random);
            List<Criterion> more = new ArrayList<>(before.criteria());
            String extra = TOKENS[6 + random.nextInt(4)];
            more.add(token("extra", extra, "avoid", 1 + random.nextInt(10)));
            Map<String, Object> facts = Map.of("title", before.facts().get("title") + " " + extra);

            int base = score(before.criteria(), facts).score();
            int after = score(more, facts).score();

            assertThat(after).as("case %d", i).isLessThanOrEqualTo(base);
        }
    }
}
