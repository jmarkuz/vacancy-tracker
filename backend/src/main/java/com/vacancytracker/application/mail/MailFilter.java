package com.vacancytracker.application.mail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the Mail screen asks for: an application, only pending suggestions, some kinds of mail, linked or not, a text.
 * The kinds are the buckets of {@link #BUCKETS}; every mail is in exactly one, so their counts add up to the total.
 */
public record MailFilter(UUID vacancyId, boolean pendingOnly, Set<String> categories, Boolean linked, String query) {

    /** A digest (two or more postings), whatever the classifier called it. */
    public static final String JOB_ALERT = "job_alert";
    /** Everything that is none of the other buckets. */
    public static final String OTHER = "other";
    /** The bucket names, in the order the screen shows them. */
    public static final List<String> BUCKETS = List.of(JOB_ALERT, "interview", "offer", "rejection", "test_task", "recruiter_message", OTHER);

    private static final int MAX_QUERY = 200;

    /** SQL for the bucket of a mail. */
    static final String BUCKET_SQL = "CASE WHEN jsonb_typeof(jobs) = 'array' AND jsonb_array_length(jobs) >= 2 THEN '" + JOB_ALERT + "'"
            + " WHEN category IN ('interview', 'offer', 'rejection', 'test_task', 'recruiter_message') THEN category ELSE '" + OTHER + "' END";

    public MailFilter {
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        query = query == null || query.isBlank() ? null : query.strip();
    }

    public static MailFilter of(UUID vacancyId, boolean pendingOnly) {
        return new MailFilter(vacancyId, pendingOnly, Set.of(), null, null);
    }

    /**
     * Reads the raw request values: categories may repeat or be comma lists. An unknown category, or a text that is
     * too long, is an {@link IllegalArgumentException} (the web layer answers 400).
     */
    public static MailFilter parse(UUID vacancyId, boolean pendingOnly, List<String> category, Boolean linked, String q) {
        Set<String> picked = new LinkedHashSet<>();
        for (String raw : category == null ? List.<String>of() : category) {
            for (String part : raw.split(",")) {
                String name = part.strip();
                if (name.isEmpty()) {
                    continue;
                }
                if (!BUCKETS.contains(name)) {
                    throw new IllegalArgumentException("Unknown mail category: " + name + ". Use " + String.join(", ", BUCKETS) + ".");
                }
                picked.add(name);
            }
        }
        if (q != null && q.strip().length() > MAX_QUERY) {
            throw new IllegalArgumentException("The search text is longer than " + MAX_QUERY + " characters.");
        }
        return new MailFilter(vacancyId, pendingOnly, picked, linked, q);
    }

    /** The WHERE conditions (joined with AND) and their bound values; the category condition is left out when asked. */
    Where where(boolean withCategories) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (vacancyId != null) {
            conditions.add("vacancy_id = :v");
            params.put("v", vacancyId);
        }
        if (pendingOnly) {
            conditions.add("suggestion_state = 'pending'");
        }
        if (linked != null) {
            conditions.add(linked ? "vacancy_id IS NOT NULL" : "vacancy_id IS NULL");
        }
        if (withCategories && !categories.isEmpty()) {
            conditions.add("(" + BUCKET_SQL + ") IN (:cats)");
            params.put("cats", List.copyOf(categories));
        }
        if (query != null) {
            conditions.add("(subject ILIKE :q ESCAPE '\\' OR sender ILIKE :q ESCAPE '\\' OR sender_name ILIKE :q ESCAPE '\\'"
                    + " OR snippet ILIKE :q ESCAPE '\\')");
            params.put("q", "%" + escapeLike(query) + "%");
        }
        return new Where(conditions.isEmpty() ? "TRUE" : String.join(" AND ", conditions), params);
    }

    /** Makes %, _ and the escape character itself match literally. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A condition text that contains only fixed SQL and :names, and the values to bind to them. */
    record Where(String sql, Map<String, Object> params) {
    }
}
