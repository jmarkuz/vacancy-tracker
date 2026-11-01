package com.vacancytracker.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.common.SqlFragment;
import com.vacancytracker.vacancy.VacancyStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Translates view-filter criteria into a parameterized SQL predicate (T-020).
 * Every criterion becomes COALESCE(core, FALSE): a missing value never satisfies a positive
 * operator. Negative operators require the field to be present. Exclude negates the match.
 * Field and operator names are checked against fixed tables; values are always bound parameters.
 */
@Component
public class CriterionCompiler {

    private static final TypeReference<List<Criterion>> LIST = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public CriterionCompiler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Parses a JSON array of criteria. Malformed JSON is a CriterionException. */
    public List<Criterion> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, LIST);
        } catch (JsonProcessingException e) {
            throw new CriterionException("Criteria must be a JSON array of criterion objects");
        }
    }

    public SqlFragment compile(List<Criterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return SqlFragment.ALL;
        }
        Params params = new Params();
        List<String> parts = new ArrayList<>();
        for (Criterion c : criteria) {
            parts.add(compileOne(c, params));
        }
        return new SqlFragment(String.join(" AND ", parts), params.values);
    }

    /** Validates criteria without running them; used by saved views. */
    public void validate(List<Criterion> criteria) {
        compile(criteria);
    }

    private String compileOne(Criterion c, Params p) {
        CriterionField field = CriterionField.of(c.field());
        String op = c.operator();
        if (op == null || !field.kind().operators.contains(op)) {
            throw new CriterionException("Operator " + op + " is not allowed for field " + c.field());
        }
        String mode = c.mode() == null ? "require" : c.mode();
        if (!"require".equals(mode) && !"exclude".equals(mode)) {
            throw new CriterionException("Views accept only require and exclude modes");
        }
        String core = predicate(field, op, c.value(), p);
        String satisfied = "COALESCE(" + core + ", FALSE)";
        return "exclude".equals(mode) ? "(NOT " + satisfied + ")" : "(" + satisfied + ")";
    }

    private String predicate(CriterionField field, String op, JsonNode value, Params p) {
        return switch (field.kind()) {
            case TEXT -> text(field.columns(), op, value, p);
            case KEYWORDS -> keywords(field.columns(), op, value, p);
            case STATUS -> status(op, value, p);
            case NUMBER -> number(field.columns().get(0), op, value, p);
            case SALARY -> salary(op, value, p);
            case DATE -> date(field.columns().get(0), op, value, p);
            case TAGS -> tags(op, value, p);
            case SOURCE -> source(op, value, p);
        };
    }

    private static String text(List<String> cols, String op, JsonNode value, Params p) {
        String col = cols.get(0);
        return switch (op) {
            case "contains_any" -> anyContains(cols, strings(value, op), p);
            case "contains_all" -> allContains(cols, strings(value, op), p);
            case "not_contains" -> "(" + presence(cols) + " AND NOT " + anyContains(cols, strings(value, op), p) + ")";
            case "equals" -> "lower(" + col + ") = lower(" + p.add(text(value, op)) + ")";
            case "in" -> "lower(" + col + ") IN (" + bindAll(strings(value, op), p, true) + ")";
            case "not_in" -> "(" + col + " IS NOT NULL AND lower(" + col + ") NOT IN (" + bindAll(strings(value, op), p, true) + "))";
            case "regex" -> regex(col, text(value, op), p);
            case "exists" -> "(" + presence(cols) + ")";
            case "not_exists" -> "(NOT (" + presence(cols) + "))";
            default -> throw new CriterionException("Unsupported operator " + op);
        };
    }

    private static String keywords(List<String> cols, String op, JsonNode value, Params p) {
        return switch (op) {
            case "contains_any" -> anyContains(cols, strings(value, op), p);
            case "contains_all" -> allContains(cols, strings(value, op), p);
            case "not_contains" -> "(" + presence(cols) + " AND NOT " + anyContains(cols, strings(value, op), p) + ")";
            default -> throw new CriterionException("Unsupported operator " + op + " for keywords");
        };
    }

    private static String status(String op, JsonNode value, Params p) {
        return switch (op) {
            case "equals" -> "status = " + p.add(statusLabel(text(value, op)));
            case "in" -> "status IN (" + bindAll(statusLabels(value, op), p, false) + ")";
            case "not_in" -> "(status IS NOT NULL AND status NOT IN (" + bindAll(statusLabels(value, op), p, false) + "))";
            default -> throw new CriterionException("Unsupported operator " + op + " for status");
        };
    }

    private static String number(String col, String op, JsonNode value, Params p) {
        return switch (op) {
            case "equals" -> col + " = " + p.add(decimal(value, op));
            case "in" -> col + " IN (" + bindAll(decimals(value, op), p, false) + ")";
            case "not_in" -> "(" + col + " IS NOT NULL AND " + col + " NOT IN (" + bindAll(decimals(value, op), p, false) + "))";
            case "gte" -> col + " >= " + p.add(decimal(value, op));
            case "lte" -> col + " <= " + p.add(decimal(value, op));
            case "between" -> {
                List<BigDecimal> range = decimals(value, op);
                if (range.size() != 2) {
                    throw new CriterionException("between needs [min, max]");
                }
                yield col + " BETWEEN " + p.add(range.get(0)) + " AND " + p.add(range.get(1));
            }
            case "exists" -> "(" + col + " IS NOT NULL)";
            case "not_exists" -> "(" + col + " IS NULL)";
            default -> throw new CriterionException("Unsupported operator " + op + " for number field");
        };
    }

    /** Salary is compared on the stored range: max if present, else min (ADR-011 normalizes on import). */
    private static String salary(String op, JsonNode value, Params p) {
        String upper = "COALESCE(salary_max, salary_min)";
        String lower = "COALESCE(salary_min, salary_max)";
        return switch (op) {
            case "gte" -> upper + " >= " + p.add(decimal(value, op));
            case "lte" -> lower + " <= " + p.add(decimal(value, op));
            case "between" -> {
                List<BigDecimal> range = decimals(value, op);
                if (range.size() != 2) {
                    throw new CriterionException("between needs [min, max]");
                }
                yield "(" + upper + " >= " + p.add(range.get(0)) + " AND " + lower + " <= " + p.add(range.get(1)) + ")";
            }
            case "exists" -> "(salary_min IS NOT NULL OR salary_max IS NOT NULL)";
            case "not_exists" -> "(salary_min IS NULL AND salary_max IS NULL)";
            default -> throw new CriterionException("Unsupported operator " + op + " for salary");
        };
    }

    private static String date(String col, String op, JsonNode value, Params p) {
        return switch (op) {
            case "gte" -> col + " >= " + p.add(instant(value, op));
            case "lte" -> col + " <= " + p.add(instant(value, op));
            case "between" -> {
                List<JsonNode> range = nodes(value, op);
                if (range.size() != 2) {
                    throw new CriterionException("between needs [from, to]");
                }
                yield col + " BETWEEN " + p.add(instant(range.get(0), op)) + " AND " + p.add(instant(range.get(1), op));
            }
            case "exists" -> "(" + col + " IS NOT NULL)";
            case "not_exists" -> "(" + col + " IS NULL)";
            default -> throw new CriterionException("Unsupported operator " + op + " for date field");
        };
    }

    private static String tags(String op, JsonNode value, Params p) {
        return switch (op) {
            case "contains_any" -> "tags && " + textArray(value, op, p);
            case "contains_all" -> "tags @> " + textArray(value, op, p);
            case "not_contains" -> "(cardinality(tags) > 0 AND NOT (tags && " + textArray(value, op, p) + "))";
            case "exists" -> "(cardinality(tags) > 0)";
            case "not_exists" -> "(cardinality(tags) = 0)";
            default -> throw new CriterionException("Unsupported operator " + op + " for tags");
        };
    }

    /** String[] is bound as varchar[]; the explicit cast keeps tags (text[]) comparable. */
    private static String textArray(JsonNode value, String op, Params p) {
        return "CAST(" + p.add(strings(value, op).toArray(String[]::new)) + " AS TEXT[])";
    }

    private static String source(String op, JsonNode value, Params p) {
        String match = switch (op) {
            case "equals" -> "s.adapter = " + p.add(text(value, op));
            case "in" -> "s.adapter IN (" + bindAll(strings(value, op), p, false) + ")";
            case "not_in" -> "s.adapter IN (" + bindAll(strings(value, op), p, false) + ")";
            default -> throw new CriterionException("Unsupported operator " + op + " for source");
        };
        String exists = "EXISTS (SELECT 1 FROM vacancy_source s WHERE s.vacancy_id = vacancy.id AND " + match + ")";
        return "not_in".equals(op) ? "(NOT " + exists + ")" : exists;
    }

    private static String anyContains(List<String> cols, List<String> needles, Params p) {
        List<String> parts = new ArrayList<>();
        for (String needle : needles) {
            for (String col : cols) {
                parts.add(col + " ILIKE " + p.add(likePattern(needle)) + " ESCAPE '\\'");
            }
        }
        return "(" + String.join(" OR ", parts) + ")";
    }

    private static String allContains(List<String> cols, List<String> needles, Params p) {
        return "(" + needles.stream().map(n -> anyContains(cols, List.of(n), p)).collect(Collectors.joining(" AND ")) + ")";
    }

    private static String regex(String col, String pattern, Params p) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new CriterionException("Invalid regular expression: " + e.getDescription());
        }
        return col + " ~* " + p.add(pattern);
    }

    private static String presence(List<String> cols) {
        return cols.stream().map(c -> c + " IS NOT NULL AND " + c + " <> ''").collect(Collectors.joining(" OR ", "(", ")"));
    }

    /** Binds each value as its own parameter; never builds literals. */
    private static String bindAll(List<?> values, Params p, boolean lower) {
        if (values.isEmpty()) {
            throw new CriterionException("A list operator needs at least one value");
        }
        return values.stream().map(v -> {
            String ref = p.add(v);
            return lower ? "lower(" + ref + ")" : ref;
        }).collect(Collectors.joining(", "));
    }

    private static String likePattern(String needle) {
        String escaped = needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String statusLabel(String value) {
        try {
            return VacancyStatus.fromLabel(value).label();
        } catch (IllegalArgumentException e) {
            throw new CriterionException("Unknown status: " + value);
        }
    }

    private static List<String> statusLabels(JsonNode value, String op) {
        return strings(value, op).stream().map(CriterionCompiler::statusLabel).toList();
    }

    private static String text(JsonNode value, String op) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new CriterionException(op + " needs a non-empty text value");
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode value, String op) {
        List<String> out = new ArrayList<>();
        if (value != null && value.isTextual()) {
            out.add(text(value, op));
            return out;
        }
        for (JsonNode n : nodes(value, op)) {
            out.add(text(n, op));
        }
        return out;
    }

    private static BigDecimal decimal(JsonNode value, String op) {
        if (value == null || !value.isNumber()) {
            throw new CriterionException(op + " needs a number");
        }
        return value.decimalValue();
    }

    private static List<BigDecimal> decimals(JsonNode value, String op) {
        List<BigDecimal> out = new ArrayList<>();
        for (JsonNode n : nodes(value, op)) {
            out.add(decimal(n, op));
        }
        return out;
    }

    private static Instant instant(JsonNode value, String op) {
        try {
            return Instant.parse(text(value, op));
        } catch (DateTimeParseException e) {
            throw new CriterionException(op + " needs an ISO-8601 instant");
        }
    }

    private static List<JsonNode> nodes(JsonNode value, String op) {
        if (value == null || !value.isArray()) {
            throw new CriterionException(op + " needs an array value");
        }
        List<JsonNode> out = new ArrayList<>();
        value.forEach(out::add);
        return out;
    }

    /** Named parameter registry. Values are converted to the types PostgreSQL expects. */
    private static final class Params {

        private final Map<String, Object> values = new HashMap<>();

        String add(Object value) {
            String name = "f" + values.size();
            values.put(name, value instanceof Instant i ? OffsetDateTime.ofInstant(i, ZoneOffset.UTC) : value);
            return ":" + name;
        }
    }
}
