package com.vacancytracker.matching;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Validates and normalizes a profile (docs/search-profiles.md sections 1 to 4). All problems are collected,
 * each under a path like criteria[3].operator, so the rule builder can mark the exact field.
 * The field table covers every field of the brief, not only those the SQL filters support today:
 * profiles are evaluated by the rule engine, not by SQL.
 */
@Component
public class ProfileValidator {

    enum Kind { TEXT, ENUM, NUMBER, BOOLEAN, LIST, SALARY }

    private static final Map<String, Kind> FIELDS = Map.ofEntries(
            Map.entry("title", Kind.TEXT),
            Map.entry("description", Kind.TEXT),
            Map.entry("keywords", Kind.TEXT),
            Map.entry("company", Kind.TEXT),
            Map.entry("companyType", Kind.ENUM),
            Map.entry("industry", Kind.TEXT),
            Map.entry("location", Kind.TEXT),
            Map.entry("country", Kind.ENUM),
            Map.entry("city", Kind.TEXT),
            Map.entry("workMode", Kind.ENUM),
            Map.entry("employmentType", Kind.ENUM),
            Map.entry("seniority", Kind.ENUM),
            Map.entry("salary", Kind.SALARY),
            Map.entry("workLanguage", Kind.ENUM),
            Map.entry("visaSponsorship", Kind.BOOLEAN),
            Map.entry("relocationSupport", Kind.BOOLEAN),
            Map.entry("postedAge", Kind.NUMBER),
            Map.entry("source", Kind.ENUM),
            Map.entry("tags", Kind.LIST));

    private static final Map<Kind, Set<String>> OPERATORS = Map.of(
            Kind.TEXT, Set.of("contains_any", "contains_all", "not_contains", "equals", "in", "not_in", "regex", "exists", "not_exists"),
            Kind.ENUM, Set.of("equals", "in", "not_in", "exists", "not_exists"),
            Kind.NUMBER, Set.of("equals", "gte", "lte", "between", "exists", "not_exists"),
            Kind.BOOLEAN, Set.of("equals", "exists", "not_exists"),
            Kind.LIST, Set.of("contains_any", "contains_all", "not_contains", "exists", "not_exists"),
            Kind.SALARY, Set.of("gte", "lte", "between", "exists", "not_exists"));

    /** The shape of the value an operator needs, for the rule builder and for the consistency test. */
    public enum ValueShape { NONE, LIST, TEXT, PATTERN, NUMBER, RANGE, BOOLEAN, SALARY, SALARY_RANGE }

    public record OperatorInfo(String name, ValueShape valueShape) {
    }

    public record FieldInfo(String name, String kind, List<OperatorInfo> operators) {
    }

    /** Everything the rule builder needs to offer only valid choices. Built from the validator's own tables. */
    public record Catalog(List<FieldInfo> fields, List<String> modes, List<String> missingValuePolicies,
                          int minWeight, int maxWeight, Thresholds defaultThresholds) {
    }

    private static final List<String> FIELD_ORDER = List.of("title", "description", "keywords", "company", "companyType",
            "industry", "location", "country", "city", "workMode", "employmentType", "seniority", "salary",
            "workLanguage", "visaSponsorship", "relocationSupport", "postedAge", "source", "tags");

    private static final List<String> OPERATOR_ORDER = List.of("contains_any", "contains_all", "not_contains", "equals",
            "in", "not_in", "regex", "gte", "lte", "between", "exists", "not_exists");

    public static Catalog catalog() {
        List<FieldInfo> fields = new ArrayList<>();
        for (String name : FIELD_ORDER) {
            Kind kind = FIELDS.get(name);
            List<OperatorInfo> operators = OPERATOR_ORDER.stream()
                    .filter(OPERATORS.get(kind)::contains)
                    .map(op -> new OperatorInfo(op, shapeOf(kind, op)))
                    .toList();
            fields.add(new FieldInfo(name, kind.name().toLowerCase(java.util.Locale.ROOT), operators));
        }
        return new Catalog(fields, List.of("require", "prefer", "avoid", "exclude"),
                List.of("ignore", "penalize", "hide"), 1, 10, Thresholds.defaults());
    }

    /** All field names the validator knows, in the order of the brief. */
    public static List<String> fieldNames() {
        return FIELD_ORDER;
    }

    static ValueShape shapeOf(Kind kind, String op) {
        return switch (op) {
            case "exists", "not_exists" -> ValueShape.NONE;
            case "regex" -> ValueShape.PATTERN;
            case "contains_any", "contains_all", "not_contains", "in", "not_in" -> ValueShape.LIST;
            case "between" -> kind == Kind.SALARY ? ValueShape.SALARY_RANGE : ValueShape.RANGE;
            case "gte", "lte" -> kind == Kind.SALARY ? ValueShape.SALARY : ValueShape.NUMBER;
            default -> kind == Kind.BOOLEAN ? ValueShape.BOOLEAN : kind == Kind.NUMBER ? ValueShape.NUMBER : ValueShape.TEXT;
        };
    }

    /** The value kind of a profile field, or null when the field is unknown. Shared with the rule engine. */
    static Kind kindOf(String field) {
        return field == null ? null : FIELDS.get(field);
    }

    private static final Set<String> MODES = Set.of("require", "prefer", "avoid", "exclude");
    private static final Set<String> POLICIES = Set.of("ignore", "penalize", "hide");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** Returns the profile with defaults applied (missing value policy, thresholds), or throws with all problems. */
    public ProfileInput validate(ProfileInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (in == null) {
            throw new ProfileValidationException(Map.of("profile", "A profile is required"));
        }
        validateHeader(in, errors);
        Thresholds thresholds = in.thresholds() == null ? Thresholds.defaults() : in.thresholds();
        validateThresholds(thresholds, errors);

        List<Criterion> criteria = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<Criterion> given = in.criteria() == null ? List.of() : in.criteria();
        for (int i = 0; i < given.size(); i++) {
            Criterion c = given.get(i);
            String path = "criteria[" + i + "]";
            if (c == null) {
                errors.put(path, "A criterion is required");
                continue;
            }
            validateCriterion(c, path, ids, errors);
            criteria.add(new Criterion(c.id() == null ? null : c.id().trim(), c.field(), c.operator(), c.value(), c.mode(),
                    c.weight(), c.missingValuePolicy() == null ? "ignore" : c.missingValuePolicy(), c.label()));
        }
        if (!errors.isEmpty()) {
            throw new ProfileValidationException(errors);
        }
        return new ProfileInput(in.name().trim(), in.enabled(), in.sources().stream().map(String::trim).toList(),
                in.scheduleOverride() == null || in.scheduleOverride().isBlank() ? null : in.scheduleOverride().trim(),
                thresholds, List.copyOf(criteria));
    }

    private static void validateHeader(ProfileInput in, Map<String, String> errors) {
        if (in.name() == null || in.name().isBlank()) {
            errors.put("name", "Name is required");
        } else if (in.name().trim().length() > 100) {
            errors.put("name", "Name must be at most 100 characters");
        }
        if (in.sources() == null || in.sources().isEmpty()) {
            errors.put("sources", "Choose at least one source, or 'all'");
        } else if (in.sources().stream().anyMatch(s -> s == null || s.isBlank())) {
            errors.put("sources", "Source names must not be blank");
        }
        if (in.scheduleOverride() != null && !in.scheduleOverride().isBlank()
                && !CronExpression.isValidExpression(in.scheduleOverride().trim())) {
            errors.put("scheduleOverride", "Not a valid cron expression");
        }
    }

    private static void validateThresholds(Thresholds t, Map<String, String> errors) {
        if (t.inboxMinScore() < 0 || t.inboxMinScore() > 100) {
            errors.put("thresholds.inboxMinScore", "Must be between 0 and 100");
        }
        if (t.notifyMinScore() < 0 || t.notifyMinScore() > 100) {
            errors.put("thresholds.notifyMinScore", "Must be between 0 and 100");
        }
        if (t.maxAgeDays() < 1 || t.maxAgeDays() > 3650) {
            errors.put("thresholds.maxAgeDays", "Must be between 1 and 3650");
        }
    }

    private void validateCriterion(Criterion c, String path, Set<String> ids, Map<String, String> errors) {
        if (c.id() == null || c.id().isBlank()) {
            errors.put(path + ".id", "Criterion id is required");
        } else if (!ids.add(c.id().trim())) {
            errors.put(path + ".id", "Criterion id '" + c.id().trim() + "' is used twice");
        }
        if (c.mode() == null || !MODES.contains(c.mode())) {
            errors.put(path + ".mode", "Mode must be one of require, prefer, avoid, exclude");
        }
        if (c.weight() == null || c.weight() < 1 || c.weight() > 10) {
            errors.put(path + ".weight", "Weight must be between 1 and 10");
        }
        if (c.missingValuePolicy() != null && !POLICIES.contains(c.missingValuePolicy())) {
            errors.put(path + ".missingValuePolicy", "Must be one of ignore, penalize, hide");
        }
        if (c.label() != null && c.label().length() > 200) {
            errors.put(path + ".label", "Label must be at most 200 characters");
        }
        Kind kind = c.field() == null ? null : FIELDS.get(c.field());
        if (kind == null) {
            errors.put(path + ".field", "Unknown field '" + c.field() + "'");
            return;
        }
        if (c.operator() == null || !OPERATORS.get(kind).contains(c.operator())) {
            errors.put(path + ".operator", "'" + c.operator() + "' is not allowed for field '" + c.field() + "'");
            return;
        }
        String problem = valueProblem(kind, c.operator(), c.value());
        if (problem != null) {
            errors.put(path + ".value", problem);
        }
    }

    /** Returns a description of what is wrong with the value, or null when it fits the operator. */
    private static String valueProblem(Kind kind, String op, JsonNode value) {
        boolean absent = value == null || value.isNull();
        switch (op) {
            case "exists", "not_exists":
                return absent ? null : "This operator takes no value";
            case "regex":
                if (absent || !value.isTextual() || value.asText().isEmpty()) {
                    return "A pattern is required";
                }
                try {
                    Pattern.compile(value.asText());
                    return null;
                } catch (PatternSyntaxException e) {
                    return "Invalid regular expression: " + e.getDescription();
                }
            case "contains_any", "contains_all", "not_contains", "in", "not_in":
                return stringList(value);
            default:
                return scalarOrRangeProblem(kind, op, value);
        }
    }

    private static String scalarOrRangeProblem(Kind kind, String op, JsonNode value) {
        if (value == null || value.isNull()) {
            return "A value is required";
        }
        return switch (kind) {
            case SALARY -> salaryProblem(op, value);
            case NUMBER -> "between".equals(op) ? rangeProblem(value) : value.isNumber() ? null : "A number is required";
            case BOOLEAN -> value.isBoolean() ? null : "true or false is required";
            default -> value.isTextual() && !value.asText().isBlank() ? null : "A non-empty text value is required";
        };
    }

    private static String stringList(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty()) {
            return "A non-empty list of values is required";
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return "Every list item must be non-empty text";
            }
        }
        return null;
    }

    private static String rangeProblem(JsonNode value) {
        if (!value.isArray() || value.size() != 2 || !value.get(0).isNumber() || !value.get(1).isNumber()) {
            return "A range [min, max] of two numbers is required";
        }
        return value.get(0).decimalValue().compareTo(value.get(1).decimalValue()) > 0 ? "min must not be greater than max" : null;
    }

    /** Salary values are objects: amount (or min and max for between), currency, period, optional basis. */
    private static String salaryProblem(String op, JsonNode value) {
        if (!value.isObject()) {
            return "A salary object {amount, currency, period} is required";
        }
        if ("between".equals(op)) {
            if (!value.path("min").isNumber() || !value.path("max").isNumber()) {
                return "between needs numeric min and max";
            }
            if (value.get("min").decimalValue().compareTo(value.get("max").decimalValue()) > 0) {
                return "min must not be greater than max";
            }
        } else if (!value.path("amount").isNumber() || value.get("amount").decimalValue().signum() <= 0) {
            return "amount must be a positive number";
        }
        if (!value.path("currency").isTextual() || !CURRENCY.matcher(value.get("currency").asText()).matches()) {
            return "currency must be a three-letter code such as EUR";
        }
        String period = value.path("period").asText("");
        if (!"month".equals(period) && !"year".equals(period)) {
            return "period must be month or year";
        }
        String basis = value.path("basis").asText("");
        if (!basis.isEmpty() && !"gross".equals(basis) && !"net".equals(basis)) {
            return "basis must be gross or net";
        }
        return null;
    }
}
