package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.RuleEngine.Effect;
import com.vacancytracker.matching.RuleEngine.RuleResult;
import com.vacancytracker.matching.RuleEngine.SalaryFact;
import com.vacancytracker.matching.RuleEngine.Verdict;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The matrix for T-050: every operator, every mode and every missing-value policy, each with an explicit
 * expectation.
 */
class RuleEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleEngine engine = new RuleEngine(new YearlyConverter("EUR"));

    private Criterion crit(String field, String op, String value, String mode, String policy) {
        try {
            return new Criterion("c", field, op, value == null ? null : mapper.readTree(value), mode, 5, policy, null);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Verdict verdictOf(String field, String op, String value, Object fact) {
        Map<String, Object> facts = new HashMap<>();
        facts.put(field, fact);
        return engine.evaluateOne(crit(field, op, value, "prefer", null), facts).verdict();
    }

    // ---- operators on text ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "contains_any|[\"java\",\"kotlin\"]|Senior Java Developer|SATISFIED",
            "contains_any|[\"java\",\"kotlin\"]|Python Developer|NOT_SATISFIED",
            "contains_any|[\"JAVA\"]|senior java developer|SATISFIED",
            "contains_all|[\"senior\",\"java\"]|Senior Java Developer|SATISFIED",
            "contains_all|[\"senior\",\"go\"]|Senior Java Developer|NOT_SATISFIED",
            "not_contains|[\"intern\"]|Senior Java Developer|SATISFIED",
            "not_contains|[\"senior\"]|Senior Java Developer|NOT_SATISFIED",
            "equals|\"senior java developer\"|Senior Java Developer|SATISFIED",
            "equals|\"java\"|Senior Java Developer|NOT_SATISFIED",
            "in|[\"qa\",\"senior java developer\"]|Senior Java Developer|SATISFIED",
            "in|[\"qa\",\"ops\"]|Senior Java Developer|NOT_SATISFIED",
            "not_in|[\"qa\",\"ops\"]|Senior Java Developer|SATISFIED",
            "not_in|[\"senior java developer\"]|Senior Java Developer|NOT_SATISFIED",
            "regex|\"^senior\\\\s+java\"|Senior Java Developer|SATISFIED",
            "regex|\"(rust)$\"|Senior Java Developer|NOT_SATISFIED",
    })
    void textOperators(String op, String value, String title, String expected) {
        assertThat(verdictOf("title", op, value, title)).isEqualTo(Verdict.valueOf(expected));
    }

    // ---- operators on enum-like fields ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "equals|\"DE\"|de|SATISFIED",
            "equals|\"PL\"|DE|NOT_SATISFIED",
            "in|[\"DE\",\"PL\"]|pl|SATISFIED",
            "in|[\"NL\"]|DE|NOT_SATISFIED",
            "not_in|[\"NL\",\"FR\"]|DE|SATISFIED",
            "not_in|[\"DE\"]|de|NOT_SATISFIED",
    })
    void enumOperators(String op, String value, String country, String expected) {
        assertThat(verdictOf("country", op, value, country)).isEqualTo(Verdict.valueOf(expected));
    }

    // ---- operators on numbers ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "equals|14|14|SATISFIED",
            "equals|14|15|NOT_SATISFIED",
            "gte|14|14|SATISFIED",
            "gte|14|13|NOT_SATISFIED",
            "lte|14|14|SATISFIED",
            "lte|14|15|NOT_SATISFIED",
            "between|[10,20]|10|SATISFIED",
            "between|[10,20]|20|SATISFIED",
            "between|[10,20]|21|NOT_SATISFIED",
            "between|[10,20]|9|NOT_SATISFIED",
    })
    void numberOperatorsAreInclusive(String op, String value, int age, String expected) {
        assertThat(verdictOf("postedAge", op, value, age)).isEqualTo(Verdict.valueOf(expected));
    }

    // ---- operators on booleans and lists ----

    @Test
    void booleanEquals() {
        assertThat(verdictOf("visaSponsorship", "equals", "true", true)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("visaSponsorship", "equals", "true", false)).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("relocationSupport", "equals", "false", false)).isEqualTo(Verdict.SATISFIED);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "contains_any|[\"java\",\"go\"]|SATISFIED",
            "contains_any|[\"go\"]|NOT_SATISFIED",
            "contains_all|[\"java\",\"remote\"]|SATISFIED",
            "contains_all|[\"java\",\"go\"]|NOT_SATISFIED",
            "not_contains|[\"go\"]|SATISFIED",
            "not_contains|[\"remote\"]|NOT_SATISFIED",
    })
    void listOperators(String op, String value, String expected) {
        assertThat(verdictOf("tags", op, value, List.of("Java", "Remote"))).isEqualTo(Verdict.valueOf(expected));
    }

    // ---- exists and not_exists are never "missing" ----

    @Test
    void existsAndNotExistsTestPresenceForEveryKind() {
        assertThat(verdictOf("city", "exists", null, "Berlin")).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("city", "exists", null, "  ")).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("city", "not_exists", null, null)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("postedAge", "exists", null, 3)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("postedAge", "exists", null, null)).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("visaSponsorship", "exists", null, false)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("tags", "exists", null, List.of("a"))).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("tags", "exists", null, List.of())).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("tags", "not_exists", null, List.of())).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("salary", "exists", null, new SalaryFact(BigDecimal.TEN, null, "EUR", "year"))).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("salary", "exists", null, new SalaryFact(null, null, null, null))).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("salary", "not_exists", null, null)).isEqualTo(Verdict.SATISFIED);
    }

    @Test
    void existsIgnoresTheMissingValuePolicy() {
        Criterion hide = crit("city", "exists", null, "require", "hide");

        RuleResult result = engine.evaluate(List.of(hide), Map.of());

        assertThat(result.results().get(0).verdict()).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(result.results().get(0).effect()).isEqualTo(Effect.FILTER_OUT);
    }

    // ---- missing values ----

    @Test
    void absentNullAndBlankFactsAreMissingForEveryComparingOperator() {
        assertThat(verdictOf("title", "contains_any", "[\"a\"]", null)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("title", "contains_any", "[\"a\"]", "   ")).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("postedAge", "gte", "1", null)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("visaSponsorship", "equals", "true", null)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("tags", "contains_any", "[\"a\"]", null)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("country", "not_in", "[\"DE\"]", null)).isEqualTo(Verdict.MISSING);
    }

    @Test
    void anEmptyListIsKnownSoItIsNotMissing() {
        assertThat(verdictOf("tags", "contains_any", "[\"a\"]", List.of())).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("tags", "not_contains", "[\"a\"]", List.of())).isEqualTo(Verdict.SATISFIED);
    }

    // ---- salary ----

    private static final String FLOOR_50K_YEAR = "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}";

    @Test
    void salaryIsComparedOnYearlyAmounts() {
        SalaryFact monthly = new SalaryFact(new BigDecimal("4500"), null, "EUR", "month");

        // 4500 a month is 54000 a year: above a 50000 floor, below a 60000 floor.
        assertThat(verdictOf("salary", "gte", FLOOR_50K_YEAR, monthly)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("salary", "gte", "{\"amount\":60000,\"currency\":\"EUR\",\"period\":\"year\"}", monthly))
                .isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("salary", "gte", "{\"amount\":4000,\"currency\":\"EUR\",\"period\":\"month\"}", monthly))
                .isEqualTo(Verdict.SATISFIED);
    }

    @Test
    void gteUsesTheUpperEndAndLteTheLowerEnd() {
        SalaryFact range = new SalaryFact(new BigDecimal("40000"), new BigDecimal("60000"), "EUR", "year");

        assertThat(verdictOf("salary", "gte", FLOOR_50K_YEAR, range)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("salary", "gte", "{\"amount\":70000,\"currency\":\"EUR\",\"period\":\"year\"}", range))
                .isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("salary", "lte", "{\"amount\":45000,\"currency\":\"EUR\",\"period\":\"year\"}", range))
                .isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("salary", "lte", "{\"amount\":30000,\"currency\":\"EUR\",\"period\":\"year\"}", range))
                .isEqualTo(Verdict.NOT_SATISFIED);
    }

    @Test
    void betweenMeansTheRangesOverlap() {
        SalaryFact range = new SalaryFact(new BigDecimal("40000"), new BigDecimal("60000"), "EUR", "year");
        String within = "{\"min\":55000,\"max\":80000,\"currency\":\"EUR\",\"period\":\"year\"}";
        String above = "{\"min\":65000,\"max\":80000,\"currency\":\"EUR\",\"period\":\"year\"}";
        String below = "{\"min\":10000,\"max\":35000,\"currency\":\"EUR\",\"period\":\"year\"}";

        assertThat(verdictOf("salary", "between", within, range)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("salary", "between", above, range)).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOf("salary", "between", below, range)).isEqualTo(Verdict.NOT_SATISFIED);
    }

    @Test
    void aSingleStatedAmountActsAsBothEnds() {
        SalaryFact only = new SalaryFact(null, new BigDecimal("55000"), "EUR", "year");

        assertThat(verdictOf("salary", "gte", FLOOR_50K_YEAR, only)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOf("salary", "lte", FLOOR_50K_YEAR, only)).isEqualTo(Verdict.NOT_SATISFIED);
    }

    @Test
    void anUnconvertibleSalaryIsMissingData() {
        SalaryFact dollars = new SalaryFact(new BigDecimal("60000"), null, "USD", "year");
        SalaryFact hourly = new SalaryFact(new BigDecimal("30"), null, "EUR", "hour");
        SalaryFact noCurrency = new SalaryFact(new BigDecimal("60000"), null, null, "year");

        assertThat(verdictOf("salary", "gte", FLOOR_50K_YEAR, dollars)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("salary", "gte", FLOOR_50K_YEAR, hourly)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("salary", "gte", FLOOR_50K_YEAR, noCurrency)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("salary", "gte", FLOOR_50K_YEAR, null)).isEqualTo(Verdict.MISSING);
        assertThat(verdictOf("salary", "gte", "{\"amount\":50000,\"currency\":\"GBP\",\"period\":\"year\"}",
                new SalaryFact(new BigDecimal("60000"), null, "EUR", "year"))).isEqualTo(Verdict.MISSING);
    }

    // ---- modes: every mode against satisfied and not satisfied ----

    @ParameterizedTest
    @CsvSource({
            "require, SATISFIED, NONE",
            "require, NOT_SATISFIED, FILTER_OUT",
            "prefer, SATISFIED, BONUS",
            "prefer, NOT_SATISFIED, NONE",
            "avoid, SATISFIED, PENALTY",
            "avoid, NOT_SATISFIED, NONE",
            "exclude, SATISFIED, FILTER_OUT",
            "exclude, NOT_SATISFIED, NONE",
    })
    void everyModeHasTheDocumentedEffect(String mode, String verdict, String expected) {
        Criterion c = crit("title", "contains_any", "[\"java\"]", mode, null);

        assertThat(RuleEngine.effect(c, Verdict.valueOf(verdict))).isEqualTo(Effect.valueOf(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "require, NONE",
            "prefer, BONUS",
            "avoid, PENALTY",
            "exclude, FILTER_OUT",
    })
    void modesThroughTheWholeEngine(String mode, String expected) {
        RuleResult result = engine.evaluate(List.of(crit("title", "contains_any", "[\"java\"]", mode, null)),
                Map.of("title", "Java Developer"));

        assertThat(result.results().get(0).effect()).isEqualTo(Effect.valueOf(expected));
        assertThat(result.hidden()).isEqualTo(Effect.valueOf(expected) == Effect.FILTER_OUT);
    }

    // ---- missing value policies: every policy against every mode ----

    @ParameterizedTest
    @CsvSource({
            "ignore, require, NONE",
            "ignore, prefer, NONE",
            "ignore, avoid, NONE",
            "ignore, exclude, NONE",
            "penalize, require, PENALTY",
            "penalize, prefer, PENALTY",
            "penalize, avoid, PENALTY",
            "penalize, exclude, PENALTY",
            "hide, require, FILTER_OUT",
            "hide, prefer, FILTER_OUT",
            "hide, avoid, FILTER_OUT",
            "hide, exclude, FILTER_OUT",
    })
    void everyPolicyAppliesToEveryModeWhenTheFieldIsMissing(String policy, String mode, String expected) {
        Criterion c = crit("postedAge", "lte", "30", mode, policy);

        RuleResult result = engine.evaluate(List.of(c), Map.of());

        assertThat(result.results().get(0).verdict()).isEqualTo(Verdict.MISSING);
        assertThat(result.results().get(0).effect()).isEqualTo(Effect.valueOf(expected));
        assertThat(result.hidden()).isEqualTo(Effect.valueOf(expected) == Effect.FILTER_OUT);
    }

    @Test
    void theDefaultPolicyIsIgnoreSoUsefulVacanciesAreNotLost() {
        RuleResult result = engine.evaluate(List.of(crit("postedAge", "lte", "30", "require", null)), Map.of());

        assertThat(result.hidden()).isFalse();
        assertThat(result.results().get(0).effect()).isEqualTo(Effect.NONE);
    }

    // ---- the whole result ----

    @Test
    void anyFilteringCriterionHidesTheVacancyAndWeightsAreCarried() {
        List<Criterion> criteria = List.of(
                new Criterion("kw", "title", "contains_any", json("[\"java\"]"), "prefer", 8, null, null),
                new Criterion("bad", "title", "contains_any", json("[\"intern\"]"), "avoid", 3, null, null),
                new Criterion("must", "country", "in", json("[\"DE\"]"), "require", 5, null, null));

        RuleResult result = engine.evaluate(criteria, Map.of("title", "Java Intern", "country", "PL"));

        assertThat(result.hidden()).isTrue();
        assertThat(result.results()).extracting(r -> r.criterionId() + ":" + r.effect() + ":" + r.weight())
                .containsExactly("kw:BONUS:8", "bad:PENALTY:3", "must:FILTER_OUT:5");
    }

    @Test
    void aVacancyThatPassesEveryFilterStaysVisible() {
        List<Criterion> criteria = List.of(
                new Criterion("must", "country", "in", json("[\"DE\"]"), "require", 5, null, null),
                new Criterion("no", "title", "contains_any", json("[\"intern\"]"), "exclude", 5, null, null));

        RuleResult result = engine.evaluate(criteria, Map.of("title", "Senior Engineer", "country", "DE"));

        assertThat(result.hidden()).isFalse();
    }

    @Test
    void keywordsAreTitleAndDescriptionTogether() {
        Map<String, Object> facts = Map.of("title", "Engineer", "description", "We use Kotlin daily");

        assertThat(verdictOfFacts("keywords", "contains_any", "[\"kotlin\"]", facts)).isEqualTo(Verdict.SATISFIED);
        assertThat(verdictOfFacts("keywords", "contains_any", "[\"cobol\"]", facts)).isEqualTo(Verdict.NOT_SATISFIED);
        assertThat(verdictOfFacts("keywords", "contains_any", "[\"x\"]", Map.of())).isEqualTo(Verdict.MISSING);
    }

    @Test
    void unknownFieldsAreRejectedLoudly() {
        assertThatThrownBy(() -> engine.evaluate(List.of(crit("nope", "equals", "\"x\"", "prefer", null)), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown field");
    }

    @Test
    void evaluationDoesNotChangeTheFacts() {
        Map<String, Object> facts = new HashMap<>(Map.of("title", "Java Developer"));

        engine.evaluate(List.of(crit("keywords", "contains_any", "[\"java\"]", "prefer", null)), facts);

        assertThat(facts).containsOnlyKeys("title");
    }

    private Verdict verdictOfFacts(String field, String op, String value, Map<String, Object> facts) {
        return engine.evaluateOne(crit(field, op, value, "prefer", null), facts).verdict();
    }

    private com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
