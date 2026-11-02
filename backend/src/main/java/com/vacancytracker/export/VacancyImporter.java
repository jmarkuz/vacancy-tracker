package com.vacancytracker.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.common.CanonicalUrl;
import com.vacancytracker.export.BackupVacancy.BackupStatusEvent;
import com.vacancytracker.vacancy.StatusEvent;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancyState;
import com.vacancytracker.vacancy.VacancyStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Validates and stores imported vacancies row by row (T-026). Invalid rows are reported and skipped;
 * the rest are stored. A row with an existing id replaces that vacancy and its history.
 */
@Service
public class VacancyImporter {

    private static final List<String> CSV_HEADERS = List.of("id", "title", "company", "url", "location", "country", "work_mode",
            "status", "priority", "salary_min", "salary_max", "currency", "salary_period", "tags", "notes", "contact",
            "next_action_at", "next_action_note", "first_seen_at", "last_seen_at", "description", "canonical_url");

    private final VacancyService service;
    private final ObjectMapper mapper;
    private final Clock clock;

    public VacancyImporter(VacancyService service, ObjectMapper mapper, Clock clock) {
        this.service = service;
        this.mapper = mapper;
        this.clock = clock;
    }

    public ImportReport importJson(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage());
        }
        if (root == null || root.path("schemaVersion").asInt(-1) != BackupVacancy.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported backup: expected schemaVersion " + BackupVacancy.SCHEMA_VERSION);
        }
        JsonNode items = root.get("vacancies");
        if (items == null || !items.isArray()) {
            throw new IllegalArgumentException("Backup must contain a 'vacancies' array");
        }
        Report report = new Report(items.size());
        int row = 0;
        for (JsonNode item : items) {
            row++;
            try {
                BackupVacancy vacancy = mapper.treeToValue(item, BackupVacancy.class);
                store(row, vacancy, report, null);
            } catch (JsonProcessingException e) {
                report.fail(row, "record", "Not a valid vacancy object");
            } catch (IllegalArgumentException e) {
                report.fail(row, "record", e.getMessage());
            }
        }
        return report.build();
    }

    public ImportReport importCsv(String csv) {
        List<List<String>> rows;
        try {
            rows = CsvParser.parse(csv);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid CSV: " + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV is empty");
        }
        List<String> header = rows.get(0).stream().map(String::trim).toList();
        for (String name : header) {
            if (!CSV_HEADERS.contains(name)) {
                throw new IllegalArgumentException("Unknown CSV column: " + name);
            }
        }
        Report report = new Report(rows.size() - 1);
        for (int i = 1; i < rows.size(); i++) {
            int row = i;
            try {
                store(row, fromCsvRow(header, rows.get(i)), report, columnsOf(header, rows.get(i)));
            } catch (IllegalArgumentException e) {
                report.fail(row, "record", e.getMessage());
            }
        }
        return report.build();
    }

    /** csvColumns is the header of a CSV import, or null for a JSON backup, which carries whole vacancies. */
    private void store(int row, BackupVacancy b, Report report, java.util.Set<String> csvColumns) {
        VacancyState state;
        List<StatusEvent> history;
        try {
            state = toState(b);
            history = toHistory(state.id(), b.statusHistory());
        } catch (ImportFieldException e) {
            report.fail(row, e.field, e.getMessage());
            return;
        }
        try {
            if (csvColumns != null) {
                service.importCsvState(state, csvColumns);
            } else {
                service.importState(state, history);
            }
            report.ok();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            report.fail(row, "url", "Canonical URL already belongs to another vacancy");
        } catch (DataIntegrityViolationException e) {
            report.fail(row, "record", "A value is too long or not allowed (for example a currency is 3 letters)");
        }
    }

    private VacancyState toState(BackupVacancy b) {
        Instant now = clock.instant();
        String title = required(b.title(), "title");
        String company = required(b.company(), "company");
        VacancyStatus status = b.status() == null || b.status().isBlank()
                ? VacancyStatus.NEW : parseStatus(b.status(), "status");
        if (b.priority() != null && (b.priority() < 1 || b.priority() > 3)) {
            throw new ImportFieldException("priority", "priority must be between 1 and 3");
        }
        String link = CanonicalUrl.webLink(b.url());
        if (blankToNull(b.url()) != null && link == null) {
            throw new ImportFieldException("url", "url must be an http or https link");
        }
        nonNegative(b.salaryMin(), "salaryMin");
        nonNegative(b.salaryMax(), "salaryMax");
        Instant firstSeen = b.firstSeenAt() == null ? now : b.firstSeenAt();
        Instant lastSeen = b.lastSeenAt() == null ? firstSeen : b.lastSeenAt();
        return new VacancyState(
                b.id() == null ? UUID.randomUUID() : b.id(),
                title,
                company,
                link,
                link == null ? null : CanonicalUrl.of(link).orElse(null),
                blankToNull(b.location()),
                blankToNull(b.country()),
                blankToNull(b.workMode()),
                b.description(),
                b.salaryMin(),
                b.salaryMax(),
                blankToNull(b.currency()),
                blankToNull(b.salaryPeriod()),
                status,
                b.priority(),
                b.notes(),
                b.contact(),
                b.nextActionNote(),
                b.nextActionAt(),
                b.tags() == null ? List.of() : List.copyOf(b.tags()),
                b.userEditedFields() == null ? java.util.Set.of() : new LinkedHashSet<>(b.userEditedFields()),
                firstSeen,
                lastSeen,
                false,
                com.vacancytracker.vacancy.VisaSponsorship.detect(title, b.description()));
    }

    private static List<StatusEvent> toHistory(UUID vacancyId, List<BackupStatusEvent> events) {
        List<StatusEvent> result = new ArrayList<>();
        if (events == null) {
            return result;
        }
        for (BackupStatusEvent e : events) {
            if (e.to() == null || e.at() == null) {
                throw new ImportFieldException("statusHistory", "each history entry needs 'to' and 'at'");
            }
            VacancyStatus to = parseStatus(e.to(), "statusHistory.to");
            VacancyStatus from = e.from() == null ? null : parseStatus(e.from(), "statusHistory.from");
            result.add(new StatusEvent(vacancyId, from, to, e.at(), e.note()));
        }
        return result;
    }

    private BackupVacancy fromCsvRow(List<String> header, List<String> cells) {
        if (cells.size() > header.size()) {
            throw new IllegalArgumentException("Row has more cells than the header");
        }
        java.util.Map<String, String> v = new java.util.HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            v.put(header.get(i), unneutralize(cells.get(i)));
        }
        return new BackupVacancy(
                csvUuid(v.get("id")),
                v.get("title"),
                v.get("company"),
                v.get("url"),
                v.get("location"),
                v.get("country"),
                v.get("work_mode"),
                v.get("description"),
                v.get("status"),
                csvInt(v.get("priority"), "priority"),
                csvDecimal(v.get("salary_min"), "salary_min"),
                csvDecimal(v.get("salary_max"), "salary_max"),
                v.get("currency"),
                v.get("salary_period"),
                v.get("tags") == null || v.get("tags").isBlank() ? List.of()
                        : java.util.Arrays.stream(v.get("tags").split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                v.get("notes"),
                v.get("contact"),
                csvInstant(v.get("next_action_at"), "next_action_at"),
                v.get("next_action_note"),
                java.util.Set.of(),
                csvInstant(v.get("first_seen_at"), "first_seen_at"),
                csvInstant(v.get("last_seen_at"), "last_seen_at"),
                List.of());
    }

    /**
     * The columns this row speaks for: those it has a cell for (a short row says nothing about the rest), and of those
     * a blank status or date means "not given", while a blank text or number clears the stored value.
     */
    private static java.util.Set<String> columnsOf(List<String> header, List<String> cells) {
        java.util.Set<String> columns = new java.util.HashSet<>();
        for (int i = 0; i < Math.min(header.size(), cells.size()); i++) {
            String name = header.get(i);
            boolean notClearable = "status".equals(name) || "first_seen_at".equals(name) || "last_seen_at".equals(name);
            if (!(notClearable && cells.get(i).isBlank())) {
                columns.add(name);
            }
        }
        return columns;
    }

    /** Undoes the apostrophe the exporter puts before a cell that would otherwise be read as a formula. */
    /** A run of apostrophes followed by a formula character: what the exporter put one more apostrophe in front of. */
    static final java.util.regex.Pattern GUARDED = java.util.regex.Pattern.compile("'+[=+\\-@\\t\\r].*", java.util.regex.Pattern.DOTALL);

    private static String unneutralize(String cell) {
        return GUARDED.matcher(cell).matches() ? cell.substring(1) : cell;
    }

    private static UUID csvUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ImportFieldException("id", "id is not a valid UUID");
        }
    }

    private static Integer csvInt(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new ImportFieldException(field, field + " must be a whole number");
        }
    }

    private static BigDecimal csvDecimal(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new ImportFieldException(field, field + " must be a number");
        }
    }

    private static Instant csvInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ImportFieldException(field, field + " must be an ISO-8601 instant");
        }
    }

    private static VacancyStatus parseStatus(String value, String field) {
        try {
            return VacancyStatus.fromLabel(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ImportFieldException(field, "Unknown status: " + value);
        }
    }

    private static void nonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new ImportFieldException(field, field + " must not be negative");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ImportFieldException(field, field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A problem with one field of one row. Reported, never thrown out of the import. */
    static final class ImportFieldException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;
        final String field;

        ImportFieldException(String field, String message) {
            super(message);
            this.field = field;
        }
    }

    /** Collects per-row outcomes into an ImportReport. */
    private static final class Report {

        private final int total;
        private int imported;
        private final List<ImportReport.RowError> errors = new ArrayList<>();

        Report(int total) {
            this.total = total;
        }

        void ok() {
            imported++;
        }

        void fail(int row, String field, String message) {
            errors.add(new ImportReport.RowError(row, field, message));
        }

        ImportReport build() {
            return new ImportReport(total, imported, List.copyOf(errors));
        }
    }
}
