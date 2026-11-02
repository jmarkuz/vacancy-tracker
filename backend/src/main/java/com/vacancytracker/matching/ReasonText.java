package com.vacancytracker.matching;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain-language text of a criterion for match reasons: its label, or a sentence made from field, operator and value. */
final class ReasonText {

    private static final Map<String, String> OPERATORS = Map.ofEntries(
            Map.entry("contains_any", "contains any of"),
            Map.entry("contains_all", "contains all of"),
            Map.entry("not_contains", "does not contain"),
            Map.entry("equals", "is"),
            Map.entry("in", "is one of"),
            Map.entry("not_in", "is not one of"),
            Map.entry("regex", "matches"),
            Map.entry("gte", "is at least"),
            Map.entry("lte", "is at most"),
            Map.entry("between", "is between"),
            Map.entry("exists", "is stated"),
            Map.entry("not_exists", "is not stated"));

    private ReasonText() {
    }

    static String describe(Criterion c) {
        if (c.label() != null && !c.label().isBlank()) {
            return c.label().trim();
        }
        String op = OPERATORS.getOrDefault(c.operator(), c.operator());
        String value = valueText(c.value());
        return value.isEmpty() ? c.field() + " " + op : c.field() + " " + op + " " + value;
    }

    private static String valueText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(n -> parts.add(n.asText()));
            return String.join(", ", parts);
        }
        if (value.isObject()) {
            return salaryText(value);
        }
        return value.asText();
    }

    private static String salaryText(JsonNode salary) {
        String amount = salary.has("amount")
                ? salary.get("amount").asText()
                : salary.path("min").asText() + " to " + salary.path("max").asText();
        return amount + " " + salary.path("currency").asText() + " per " + salary.path("period").asText();
    }
}
