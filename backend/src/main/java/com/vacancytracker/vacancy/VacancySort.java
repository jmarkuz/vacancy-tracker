package com.vacancytracker.vacancy;

import java.util.Map;

/** Whitelisted sort keys for vacancy lists. Column names never come from the request directly. */
public record VacancySort(String column, boolean descending) {

    static final VacancySort DEFAULT = new VacancySort("last_seen_at", true);

    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("title", "title"),
            Map.entry("company", "company"),
            Map.entry("location", "location"),
            Map.entry("workMode", "work_mode"),
            Map.entry("status", "status"),
            Map.entry("priority", "priority"),
            Map.entry("salaryMax", "salary_max"),
            Map.entry("firstSeenAt", "first_seen_at"),
            Map.entry("lastSeenAt", "last_seen_at"),
            Map.entry("nextActionAt", "next_action_at"));

    /** Parses "field" or "field:asc|desc". Unknown fields are rejected. */
    public static VacancySort parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String[] parts = value.split(":", 2);
        String column = COLUMNS.get(parts[0].trim());
        if (column == null) {
            throw new IllegalArgumentException("Unknown sort field: " + parts[0]);
        }
        boolean desc = parts.length == 2 && "desc".equalsIgnoreCase(parts[1].trim());
        if (parts.length == 2 && !desc && !"asc".equalsIgnoreCase(parts[1].trim())) {
            throw new IllegalArgumentException("Sort direction must be asc or desc");
        }
        return new VacancySort(column, desc);
    }

    /** ORDER BY clause; the id tie-breaker keeps paging stable. */
    String orderBy() {
        return column + (descending ? " DESC" : " ASC") + " NULLS LAST, id";
    }
}
