package com.vacancytracker.matching;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Evaluates a profile's criteria against the facts of one vacancy (T-050, docs/search-profiles.md section 2).
 * It decides, for each criterion, whether it is satisfied, not satisfied or missing, and what that means:
 * a bonus, a penalty, filtering out, or nothing. Summing bonuses and penalties into a score is T-052.
 *
 * Facts are a map from field name to value: String, Number, Boolean, List of String, or {@link SalaryFact}.
 * A field that is absent, null or blank is missing. The engine has no I/O, so it is tested without a database.
 */
public final class RuleEngine {

    public enum Verdict { SATISFIED, NOT_SATISFIED, MISSING }

    /** What a criterion does to this vacancy. FILTER_OUT hides it; BONUS and PENALTY carry the weight. */
    public enum Effect { NONE, BONUS, PENALTY, FILTER_OUT }

    public record CriterionResult(String criterionId, Verdict verdict, Effect effect, int weight) {
    }

    public record RuleResult(boolean hidden, List<CriterionResult> results) {
    }

    /** Salary as stated by the vacancy. min or max may be null; currency and period describe both. */
    public record SalaryFact(BigDecimal min, BigDecimal max, String currency, String period) {
    }

    /** Converts an amount to a yearly amount in the profile currency. Empty means it cannot be compared. */
    public interface SalaryConverter {

        Optional<BigDecimal> toYearly(BigDecimal amount, String currency, String period);
    }

    private final SalaryConverter salary;

    public RuleEngine(SalaryConverter salary) {
        this.salary = salary;
    }

    public RuleResult evaluate(List<Criterion> criteria, Map<String, Object> facts) {
        List<CriterionResult> results = new ArrayList<>();
        boolean hidden = false;
        for (Criterion c : criteria) {
            CriterionResult result = evaluateOne(c, facts);
            results.add(result);
            hidden |= result.effect() == Effect.FILTER_OUT;
        }
        return new RuleResult(hidden, List.copyOf(results));
    }

    CriterionResult evaluateOne(Criterion c, Map<String, Object> facts) {
        ProfileValidator.Kind kind = ProfileValidator.kindOf(c.field());
        if (kind == null) {
            throw new IllegalArgumentException("Unknown field: " + c.field());
        }
        Verdict verdict = verdict(kind, c, fact(c.field(), facts));
        return new CriterionResult(c.id(), verdict, effect(c, verdict), c.weight() == null ? 0 : c.weight());
    }

    /** keywords is title and description together; every other field is read as given. */
    private static Object fact(String field, Map<String, Object> facts) {
        if ("keywords".equals(field) && !facts.containsKey("keywords")) {
            String title = facts.get("title") == null ? "" : facts.get("title").toString();
            String description = facts.get("description") == null ? "" : facts.get("description").toString();
            String both = (title + " " + description).trim();
            return both.isEmpty() ? null : both;
        }
        return facts.get(field);
    }

    static Effect effect(Criterion c, Verdict verdict) {
        return switch (verdict) {
            case SATISFIED -> switch (c.mode()) {
                case "prefer" -> Effect.BONUS;
                case "avoid" -> Effect.PENALTY;
                case "exclude" -> Effect.FILTER_OUT;
                default -> Effect.NONE;
            };
            case NOT_SATISFIED -> "require".equals(c.mode()) ? Effect.FILTER_OUT : Effect.NONE;
            case MISSING -> switch (c.missingValuePolicy() == null ? "ignore" : c.missingValuePolicy()) {
                case "penalize" -> Effect.PENALTY;
                case "hide" -> Effect.FILTER_OUT;
                default -> Effect.NONE;
            };
        };
    }

    private Verdict verdict(ProfileValidator.Kind kind, Criterion c, Object fact) {
        String op = c.operator();
        if ("exists".equals(op)) {
            return hasValue(fact) ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        if ("not_exists".equals(op)) {
            return hasValue(fact) ? Verdict.NOT_SATISFIED : Verdict.SATISFIED;
        }
        if (!known(fact)) {
            return Verdict.MISSING;
        }
        JsonNode value = c.value();
        return switch (kind) {
            case TEXT, ENUM -> flag(text(op, fact.toString(), value));
            case NUMBER -> flag(number(op, decimal(fact), value));
            case BOOLEAN -> flag(fact instanceof Boolean b && b == value.asBoolean());
            case LIST -> flag(list(op, strings(fact), value));
            case SALARY -> salaryVerdict(op, fact, value);
        };
    }

    /** known: the vacancy states something for this field. An empty list is known; a blank text is not. */
    private static boolean known(Object fact) {
        return fact != null && !(fact instanceof String s && s.isBlank());
    }

    /** hasValue: known and not an empty list. Used by exists and not_exists. */
    private static boolean hasValue(Object fact) {
        if (fact instanceof SalaryFact s) {
            return s.min() != null || s.max() != null;
        }
        return known(fact) && !(fact instanceof Collection<?> c && c.isEmpty());
    }

    private static Verdict flag(boolean satisfied) {
        return satisfied ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
    }

    private static boolean text(String op, String fact, JsonNode value) {
        String lower = fact.toLowerCase(Locale.ROOT);
        return switch (op) {
            case "contains_any" -> needles(value).stream().anyMatch(lower::contains);
            case "contains_all" -> needles(value).stream().allMatch(lower::contains);
            case "not_contains" -> needles(value).stream().noneMatch(lower::contains);
            case "equals" -> lower.equals(value.asText().toLowerCase(Locale.ROOT));
            case "in" -> needles(value).contains(lower);
            case "not_in" -> !needles(value).contains(lower);
            case "regex" -> Pattern.compile(value.asText(), Pattern.CASE_INSENSITIVE).matcher(fact).find();
            default -> throw new IllegalArgumentException("Operator " + op + " does not apply to text");
        };
    }

    private static boolean number(String op, BigDecimal fact, JsonNode value) {
        return switch (op) {
            case "equals" -> fact.compareTo(value.decimalValue()) == 0;
            case "gte" -> fact.compareTo(value.decimalValue()) >= 0;
            case "lte" -> fact.compareTo(value.decimalValue()) <= 0;
            case "between" -> fact.compareTo(value.get(0).decimalValue()) >= 0 && fact.compareTo(value.get(1).decimalValue()) <= 0;
            default -> throw new IllegalArgumentException("Operator " + op + " does not apply to numbers");
        };
    }

    private static boolean list(String op, List<String> items, JsonNode value) {
        List<String> needles = needles(value);
        return switch (op) {
            case "contains_any" -> needles.stream().anyMatch(items::contains);
            case "contains_all" -> items.containsAll(needles);
            case "not_contains" -> needles.stream().noneMatch(items::contains);
            default -> throw new IllegalArgumentException("Operator " + op + " does not apply to lists");
        };
    }

    /**
     * Salary comparison on yearly amounts in the profile currency. The upper end is the maximum (or the minimum
     * when only one is stated), as in the SQL filter: gte uses the upper end, lte the lower end, between overlap.
     * Anything that cannot be converted is missing data (ADR-011).
     */
    private Verdict salaryVerdict(String op, Object fact, JsonNode value) {
        if (!(fact instanceof SalaryFact s)) {
            throw new IllegalArgumentException("The salary fact must be a SalaryFact");
        }
        BigDecimal lowRaw = s.min() != null ? s.min() : s.max();
        BigDecimal highRaw = s.max() != null ? s.max() : s.min();
        Optional<BigDecimal> low = salary.toYearly(lowRaw, s.currency(), s.period());
        Optional<BigDecimal> high = salary.toYearly(highRaw, s.currency(), s.period());
        if (low.isEmpty() || high.isEmpty()) {
            return Verdict.MISSING;
        }
        String currency = value.path("currency").asText();
        String period = value.path("period").asText();
        if ("between".equals(op)) {
            Optional<BigDecimal> min = salary.toYearly(value.get("min").decimalValue(), currency, period);
            Optional<BigDecimal> max = salary.toYearly(value.get("max").decimalValue(), currency, period);
            if (min.isEmpty() || max.isEmpty()) {
                return Verdict.MISSING;
            }
            return flag(high.get().compareTo(min.get()) >= 0 && low.get().compareTo(max.get()) <= 0);
        }
        Optional<BigDecimal> threshold = salary.toYearly(value.get("amount").decimalValue(), currency, period);
        if (threshold.isEmpty()) {
            return Verdict.MISSING;
        }
        return switch (op) {
            case "gte" -> flag(high.get().compareTo(threshold.get()) >= 0);
            case "lte" -> flag(low.get().compareTo(threshold.get()) <= 0);
            default -> throw new IllegalArgumentException("Operator " + op + " does not apply to salary");
        };
    }

    private static List<String> needles(JsonNode value) {
        List<String> out = new ArrayList<>();
        value.forEach(n -> out.add(n.asText().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static List<String> strings(Object fact) {
        if (!(fact instanceof Collection<?> items)) {
            throw new IllegalArgumentException("A list field needs a list fact");
        }
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static BigDecimal decimal(Object fact) {
        if (fact instanceof BigDecimal b) {
            return b;
        }
        if (fact instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        throw new IllegalArgumentException("A numeric field needs a number fact");
    }
}
