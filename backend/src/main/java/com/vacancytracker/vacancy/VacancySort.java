package com.vacancytracker.vacancy;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Whitelisted sort keys for vacancy lists. Column names never come from the request directly.
 *
 * <p>Every key sorts the way its table column reads: text without regard to case (and an empty text is "no value"
 * like a missing one), status in pipeline order, salary by the highest amount stated (the lowest when only that is
 * given, so a "from 50 000" vacancy is not ranked below one with no salary at all). Vacancies without a value come
 * last in both directions, and the id breaks ties, so a page never repeats or skips a row.
 */
public record VacancySort(String column, boolean descending) {

    static final VacancySort DEFAULT = new VacancySort("last_seen_at", true);

    /** Statuses in pipeline order (the enum order), so "sort by status" follows the stages, not the alphabet. */
    private static final String STATUS_STAGE = "CASE status "
            + Arrays.stream(VacancyStatus.values())
                    .map(s -> "WHEN '" + s.label() + "' THEN " + s.ordinal())
                    .collect(Collectors.joining(" "))
            + " ELSE " + VacancyStatus.values().length + " END";

    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("title", "lower(title)"),
            Map.entry("company", "lower(company)"),
            Map.entry("location", "lower(NULLIF(btrim(location), ''))"),
            Map.entry("country", "upper(NULLIF(btrim(country), ''))"),
            Map.entry("workMode", "lower(NULLIF(btrim(work_mode), ''))"),
            Map.entry("status", STATUS_STAGE),
            Map.entry("priority", "priority"),
            Map.entry("salaryMax", "COALESCE(salary_max, salary_min)"),
            Map.entry("matchScore", "match_score"),
            Map.entry("visaSponsorship", "visa_sponsorship"),
            Map.entry("firstSeenAt", "first_seen_at"),
            Map.entry("lastSeenAt", "last_seen_at"),
            Map.entry("postedAt", "posted_at"),
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
