package com.vacancytracker.matching;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fields that can be filtered today. Brief fields not stored yet (city, industry, ...)
 * are rejected until their columns exist (M1 follow-up, see PLAN.md).
 */
public enum CriterionField {
    TITLE("title", Kind.TEXT, List.of("title")),
    COMPANY("company", Kind.TEXT, List.of("company")),
    LOCATION("location", Kind.TEXT, List.of("location")),
    DESCRIPTION("description", Kind.TEXT, List.of("description")),
    KEYWORDS("keywords", Kind.KEYWORDS, List.of("title", "description")),
    COUNTRY("country", Kind.TEXT, List.of("country")),
    WORK_MODE("workMode", Kind.TEXT, List.of("work_mode")),
    STATUS("status", Kind.STATUS, List.of("status")),
    PRIORITY("priority", Kind.NUMBER, List.of("priority")),
    SALARY("salary", Kind.SALARY, List.of("salary_min", "salary_max")),
    TAGS("tags", Kind.TAGS, List.of("tags")),
    SOURCE("source", Kind.SOURCE, List.of()),
    FIRST_SEEN_AT("firstSeenAt", Kind.DATE, List.of("first_seen_at")),
    LAST_SEEN_AT("lastSeenAt", Kind.DATE, List.of("last_seen_at")),
    NEXT_ACTION_AT("nextActionAt", Kind.DATE, List.of("next_action_at"));

    enum Kind {
        TEXT(Set.of("contains_any", "contains_all", "not_contains", "equals", "in", "not_in", "regex", "exists", "not_exists")),
        KEYWORDS(Set.of("contains_any", "contains_all", "not_contains")),
        STATUS(Set.of("equals", "in", "not_in")),
        NUMBER(Set.of("equals", "in", "not_in", "gte", "lte", "between", "exists", "not_exists")),
        SALARY(Set.of("gte", "lte", "between", "exists", "not_exists")),
        DATE(Set.of("gte", "lte", "between", "exists", "not_exists")),
        TAGS(Set.of("contains_any", "contains_all", "not_contains", "exists", "not_exists")),
        SOURCE(Set.of("equals", "in", "not_in"));

        final Set<String> operators;

        Kind(Set<String> operators) {
            this.operators = operators;
        }
    }

    private static final Map<String, CriterionField> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(f -> f.json, f -> f));

    private final String json;
    private final Kind kind;
    private final List<String> columns;

    CriterionField(String json, Kind kind, List<String> columns) {
        this.json = json;
        this.kind = kind;
        this.columns = columns;
    }

    public String json() {
        return json;
    }

    Kind kind() {
        return kind;
    }

    List<String> columns() {
        return columns;
    }

    public static CriterionField of(String name) {
        CriterionField field = name == null ? null : BY_NAME.get(name);
        if (field == null) {
            throw new CriterionException("Unknown or not yet supported field: " + name);
        }
        return field;
    }
}
