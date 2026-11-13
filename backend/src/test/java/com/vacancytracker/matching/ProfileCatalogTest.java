package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.ProfileValidator.Catalog;
import com.vacancytracker.matching.ProfileValidator.FieldInfo;
import com.vacancytracker.matching.ProfileValidator.OperatorInfo;
import com.vacancytracker.matching.ProfileValidator.ValueShape;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The catalog is what the rule builder offers, so it must agree with the validator that accepts profiles and
 * with the rule engine that evaluates them: every offered choice validates and evaluates, nothing else validates.
 */
class ProfileCatalogTest {

    private static final List<String> ALL_OPERATORS = List.of("contains_any", "contains_all", "not_contains", "equals",
            "in", "not_in", "regex", "gte", "lte", "between", "exists", "not_exists");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfileValidator validator = new ProfileValidator();
    private final RuleEngine engine = new RuleEngine(new YearlyConverter());
    private final Catalog catalog = ProfileValidator.catalog();

    private JsonNode sample(ValueShape shape) {
        String json = switch (shape) {
            case NONE -> null;
            case LIST -> "[\"a\"]";
            case TEXT -> "\"a\"";
            case PATTERN -> "\"a.*\"";
            case NUMBER -> "5";
            case RANGE -> "[1,2]";
            case BOOLEAN -> "true";
            case SALARY -> "{\"amount\":1,\"currency\":\"EUR\",\"period\":\"year\"}";
            case SALARY_RANGE -> "{\"min\":1,\"max\":2,\"currency\":\"EUR\",\"period\":\"year\"}";
        };
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ProfileInput profileWith(Criterion c) {
        return new ProfileInput("Example", true, List.of("all"), null, Thresholds.defaults(), List.of(c));
    }

    private Criterion criterion(String field, String op, JsonNode value) {
        return new Criterion("c", field, op, value, "prefer", 5, null, null);
    }

    private Object plausibleFact(String kind) {
        return switch (kind) {
            case "number" -> 5;
            case "boolean" -> true;
            case "list" -> List.of("a");
            case "salary" -> new RuleEngine.SalaryFact(BigDecimal.TEN, null, "EUR", "year");
            default -> "abc";
        };
    }

    @Test
    void theCatalogListsEveryFieldOfTheBriefInOrderAndTheGhostSignsAfterPostedAge() {
        // ghostSignals is the tracker's own addition to the brief's fields: how many of the "likely ghost" signs a vacancy shows.
        assertThat(catalog.fields()).extracting(FieldInfo::name).containsExactly("title", "description", "keywords",
                "company", "companyType", "industry", "location", "country", "city", "workMode", "employmentType",
                "seniority", "salary", "workLanguage", "visaSponsorship", "relocationSupport", "postedAge", "ghostSignals",
                "source", "tags");
        assertThat(catalog.fields()).extracting(FieldInfo::name).containsExactlyElementsOf(ProfileValidator.fieldNames());
        assertThat(catalog.modes()).containsExactly("require", "prefer", "avoid", "exclude");
        assertThat(catalog.missingValuePolicies()).containsExactly("ignore", "penalize", "hide");
        assertThat(catalog.minWeight()).isEqualTo(1);
        assertThat(catalog.maxWeight()).isEqualTo(10);
    }

    @Test
    void valueShapesDescribeWhatEachOperatorNeeds() {
        assertThat(shape("salary", "gte")).isEqualTo(ValueShape.SALARY);
        assertThat(shape("salary", "between")).isEqualTo(ValueShape.SALARY_RANGE);
        assertThat(shape("postedAge", "between")).isEqualTo(ValueShape.RANGE);
        assertThat(shape("postedAge", "lte")).isEqualTo(ValueShape.NUMBER);
        assertThat(shape("visaSponsorship", "equals")).isEqualTo(ValueShape.BOOLEAN);
        assertThat(shape("title", "regex")).isEqualTo(ValueShape.PATTERN);
        assertThat(shape("title", "equals")).isEqualTo(ValueShape.TEXT);
        assertThat(shape("tags", "contains_all")).isEqualTo(ValueShape.LIST);
        assertThat(shape("country", "exists")).isEqualTo(ValueShape.NONE);
    }

    @Test
    void everyOfferedChoiceValidatesAndEvaluates() {
        for (FieldInfo field : catalog.fields()) {
            assertThat(field.operators()).as(field.name()).isNotEmpty();
            for (OperatorInfo op : field.operators()) {
                Criterion c = criterion(field.name(), op.name(), sample(op.valueShape()));

                ProfileInput accepted = validator.validate(profileWith(c));
                assertThat(accepted.criteria()).as(field.name() + " " + op.name()).hasSize(1);

                Map<String, Object> present = new HashMap<>();
                present.put(field.name(), plausibleFact(field.kind()));
                RuleEngine.RuleResult withFact = engine.evaluate(List.of(c), present);
                RuleEngine.RuleResult withoutFact = engine.evaluate(List.of(c), Map.of());
                assertThat(withFact.results()).as(field.name() + " " + op.name() + " with a fact").hasSize(1);
                assertThat(withoutFact.results()).as(field.name() + " " + op.name() + " without a fact").hasSize(1);
            }
        }
    }

    @Test
    void everyOperatorThatIsNotOfferedIsRejected() {
        for (FieldInfo field : catalog.fields()) {
            Set<String> offered = Set.copyOf(field.operators().stream().map(OperatorInfo::name).toList());
            for (String op : ALL_OPERATORS) {
                if (offered.contains(op)) {
                    continue;
                }
                Criterion c = criterion(field.name(), op, sample(ValueShape.LIST));
                try {
                    validator.validate(profileWith(c));
                    throw new AssertionError(field.name() + " " + op + " should have been rejected");
                } catch (ProfileValidationException e) {
                    assertThat(e.errors()).as(field.name() + " " + op).containsKey("criteria[0].operator");
                }
            }
        }
    }

    private ValueShape shape(String field, String op) {
        return catalog.fields().stream().filter(f -> f.name().equals(field)).findFirst().orElseThrow()
                .operators().stream().filter(o -> o.name().equals(op)).findFirst().orElseThrow().valueShape();
    }
}
