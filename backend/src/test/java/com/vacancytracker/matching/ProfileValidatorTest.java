package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfileValidator validator = new ProfileValidator();

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Criterion c(String id, String field, String op, String value, String mode, Integer weight) {
        return new Criterion(id, field, op, value == null ? null : json(value), mode, weight, null, null);
    }

    private ProfileInput profile(Criterion... criteria) {
        return new ProfileInput("Example", true, List.of("all"), null, new Thresholds(40, 70, 60), List.of(criteria));
    }

    private Map<String, String> errorsOf(ProfileInput input) {
        try {
            validator.validate(input);
        } catch (ProfileValidationException e) {
            return e.errors();
        }
        throw new AssertionError("Expected the profile to be rejected");
    }

    @Test
    void acceptsAValidProfileAndAppliesTheDefaultMissingValuePolicy() {
        ProfileInput ok = validator.validate(profile(
                c("kw", "keywords", "contains_any", "[\"a\",\"b\"]", "prefer", 8),
                c("sal", "salary", "gte", "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\",\"basis\":\"gross\"}", "prefer", 6),
                c("vis", "visaSponsorship", "equals", "true", "prefer", 3),
                c("age", "postedAge", "lte", "14", "prefer", 3)));

        assertThat(ok.criteria()).hasSize(4);
        assertThat(ok.criteria()).allSatisfy(x -> assertThat(x.missingValuePolicy()).isEqualTo("ignore"));
    }

    @Test
    void reportsEveryProblemWithItsPath() {
        Map<String, String> errors = errorsOf(new ProfileInput(" ", true, List.of(), "not a cron",
                new Thresholds(120, -1, 0), List.of(
                c("a", "salary", "regex", "\"x\"", "prefer", 5),
                c("a", "nope", "equals", "\"x\"", "prefer", 5),
                c("b", "title", "contains_any", "[]", "maybe", 11))));

        assertThat(errors).containsKeys("name", "sources", "scheduleOverride", "thresholds.inboxMinScore",
                "thresholds.notifyMinScore", "thresholds.maxAgeDays", "criteria[0].operator", "criteria[1].id",
                "criteria[1].field", "criteria[2].mode", "criteria[2].weight", "criteria[2].value");
        assertThat(errors.get("criteria[0].operator")).isEqualTo("'regex' is not allowed for field 'salary'");
    }

    @Test
    void valuesMustFitTheOperator() {
        assertThat(errorsOf(profile(c("x", "title", "regex", "\"([\"", "prefer", 1))).get("criteria[0].value"))
                .startsWith("Invalid regular expression");
        assertThat(errorsOf(profile(c("x", "postedAge", "between", "[10,5]", "prefer", 1))).get("criteria[0].value"))
                .isEqualTo("min must not be greater than max");
        assertThat(errorsOf(profile(c("x", "visaSponsorship", "equals", "\"yes\"", "prefer", 1))).get("criteria[0].value"))
                .isEqualTo("true or false is required");
        assertThat(errorsOf(profile(c("x", "title", "exists", "\"x\"", "prefer", 1))).get("criteria[0].value"))
                .isEqualTo("This operator takes no value");
        assertThat(errorsOf(profile(c("x", "country", "in", "[\"DE\",\"\"]", "prefer", 1))).get("criteria[0].value"))
                .isEqualTo("Every list item must be non-empty text");
    }

    @Test
    void salaryValuesNeedAmountCurrencyAndPeriod() {
        assertThat(errorsOf(profile(c("x", "salary", "gte", "{\"amount\":0,\"currency\":\"EUR\",\"period\":\"year\"}", "prefer", 1)))
                .get("criteria[0].value")).isEqualTo("amount must be a positive number");
        assertThat(errorsOf(profile(c("x", "salary", "gte", "{\"amount\":5,\"currency\":\"eur\",\"period\":\"year\"}", "prefer", 1)))
                .get("criteria[0].value")).startsWith("currency must be");
        assertThat(errorsOf(profile(c("x", "salary", "gte", "{\"amount\":5,\"currency\":\"EUR\",\"period\":\"week\"}", "prefer", 1)))
                .get("criteria[0].value")).isEqualTo("period must be month or year");
        assertThat(validator.validate(profile(c("x", "salary", "between",
                "{\"min\":40000,\"max\":60000,\"currency\":\"EUR\",\"period\":\"year\"}", "prefer", 1))).criteria()).hasSize(1);
    }

    @Test
    void nullThresholdsGetDefaultsAndCronIsOptional() {
        ProfileInput ok = validator.validate(new ProfileInput("Example", true, List.of("arbeitnow"), "0 0 6 * * *", null, List.of()));

        assertThat(ok.thresholds()).isEqualTo(Thresholds.defaults());
        assertThat(ok.scheduleOverride()).isEqualTo("0 0 6 * * *");
    }
}
