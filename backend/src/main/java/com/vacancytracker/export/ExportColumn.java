package com.vacancytracker.export;

import com.vacancytracker.vacancy.Vacancy;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** Columns available for CSV and XLSX export. The id is the name used in the columns parameter. */
public enum ExportColumn {
    ID("id", v -> v.getId().toString()),
    TITLE("title", Vacancy::getTitle),
    COMPANY("company", Vacancy::getCompany),
    URL("url", Vacancy::getUrl),
    CANONICAL_URL("canonical_url", Vacancy::getCanonicalUrl),
    LOCATION("location", Vacancy::getLocation),
    WORK_MODE("work_mode", Vacancy::getWorkMode),
    STATUS("status", v -> v.getStatus().label()),
    PRIORITY("priority", Vacancy::getPriority),
    SALARY_MIN("salary_min", Vacancy::getSalaryMin),
    SALARY_MAX("salary_max", Vacancy::getSalaryMax),
    CURRENCY("currency", Vacancy::getCurrency),
    SALARY_PERIOD("salary_period", Vacancy::getSalaryPeriod),
    TAGS("tags", v -> String.join("; ", v.getTags())),
    NOTES("notes", Vacancy::getNotes),
    CONTACT("contact", Vacancy::getContact),
    NEXT_ACTION_AT("next_action_at", v -> v.getNextActionAt() == null ? null : v.getNextActionAt().toString()),
    NEXT_ACTION_NOTE("next_action_note", Vacancy::getNextActionNote),
    FIRST_SEEN_AT("first_seen_at", v -> v.getFirstSeenAt().toString()),
    LAST_SEEN_AT("last_seen_at", v -> v.getLastSeenAt() == null ? null : v.getLastSeenAt().toString()),
    DESCRIPTION("description", Vacancy::getDescription);

    /** Columns exported when the request does not name any. */
    public static final List<ExportColumn> DEFAULT = List.of(ID, TITLE, COMPANY, URL, LOCATION, WORK_MODE, STATUS,
            PRIORITY, SALARY_MIN, SALARY_MAX, CURRENCY, SALARY_PERIOD, TAGS, NOTES, CONTACT, NEXT_ACTION_AT,
            FIRST_SEEN_AT, LAST_SEEN_AT);

    private final String id;
    private final Function<Vacancy, Object> value;

    ExportColumn(String id, Function<Vacancy, Object> value) {
        this.id = id;
        this.value = value;
    }

    public String id() {
        return id;
    }

    /** Raw value: BigDecimal and Integer stay numeric for XLSX; null means an empty cell. */
    public Object value(Vacancy vacancy) {
        return value.apply(vacancy);
    }

    public static List<ExportColumn> parse(String csvIds) {
        if (csvIds == null || csvIds.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(csvIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Arrays.stream(values())
                        .filter(c -> c.id.equals(s))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown export column: " + s)))
                .toList();
    }
}
