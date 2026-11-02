package com.vacancytracker.vacancy;

import com.vacancytracker.common.CanonicalUrl;
import java.time.Clock;
import java.time.Instant;
import com.vacancytracker.common.SqlFragment;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for manual vacancy management (T-015). Automation uses other entry points later. */
@Service
public class VacancyService {

    private final VacancyRepository repository;
    private final Clock clock;

    public VacancyService(VacancyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Vacancy create(String title, String company, String url, String description) {
        Instant now = clock.instant();
        String canonical = CanonicalUrl.of(url).orElse(null);
        Vacancy vacancy = Vacancy.discovered(UUID.randomUUID(), title, company, blankToNull(url), canonical,
                description, now);
        repository.save(vacancy.toState());
        return vacancy;
    }

    /** Creates a vacancy from an imported page, including placement and salary. */
    @Transactional
    public Vacancy createImported(String url, ImportedPosting job) {
        Vacancy vacancy = create(job.title(), job.company(), url, job.description());
        vacancy.setPlacement(job.location(), null, job.workMode());
        vacancy.applyAutomated(new AutomatedFields(null, job.salaryMin(), job.salaryMax(), job.currency(),
                job.salaryPeriod(), clock.instant()));
        repository.save(vacancy.toState());
        return vacancy;
    }

    @Transactional(readOnly = true)
    public Vacancy get(UUID id) {
        return repository.findById(id).map(Vacancy::restore).orElseThrow(() -> new VacancyNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Vacancy> list(VacancyStatus status, int page, int size) {
        return search(statusFilter(status), VacancySort.DEFAULT, page, size);
    }

    @Transactional(readOnly = true)
    public long count(VacancyStatus status) {
        return countMatching(statusFilter(status));
    }

    /** Vacancies matching a compiled filter, in the given order. Page is zero-based. */
    @Transactional(readOnly = true)
    public List<Vacancy> search(SqlFragment where, VacancySort sort, int page, int size) {
        return repository.findPage(where, sort, size, page * size).stream().map(Vacancy::restore).toList();
    }

    @Transactional(readOnly = true)
    public long countMatching(SqlFragment where) {
        return repository.count(where);
    }

    private static SqlFragment statusFilter(VacancyStatus status) {
        return status == null ? SqlFragment.ALL
                : new SqlFragment("status = :status", Map.of("status", status.label()));
    }

    @Transactional
    public Vacancy update(UUID id, UpdateCommand cmd) {
        Vacancy vacancy = get(id);
        if (cmd.description() != null) {
            vacancy.editDescription(cmd.description());
        }
        if (cmd.notes() != null) {
            vacancy.setNotes(cmd.notes());
        }
        if (cmd.contact() != null) {
            vacancy.setContact(cmd.contact());
        }
        if (cmd.priority() != null) {
            vacancy.setPriority(cmd.priority());
        }
        if (cmd.tags() != null) {
            vacancy.setTags(cmd.tags());
        }
        if (cmd.nextActionAt() != null || cmd.nextActionNote() != null) {
            vacancy.setNextAction(cmd.nextActionAt(), cmd.nextActionNote());
        }
        repository.save(vacancy.toState());
        return vacancy;
    }

    @Transactional
    public Vacancy changeStatus(UUID id, VacancyStatus to, String note) {
        Vacancy vacancy = get(id);
        StatusEvent event = vacancy.changeStatus(to, clock.instant(), note);
        repository.save(vacancy.toState());
        repository.appendStatusEvent(event);
        return vacancy;
    }

    /**
     * Stores an imported vacancy with its history, replacing any existing row with the same id.
     * Each row is its own transaction so one bad row does not undo the others.
     */
    @Transactional
    public void importState(VacancyState state, List<StatusEvent> history) {
        repository.save(state);
        repository.deleteHistory(state.id());
        history.forEach(repository::appendStatusEvent);
    }

    /**
     * Stores a vacancy read from a CSV row. CSV carries no description, edit marks or history, so for a vacancy that
     * already exists those are kept as they are and a changed status is appended to its history, not replacing it.
     */
    @Transactional
    public void importCsvState(VacancyState row, java.util.Set<String> columns) {
        var existing = repository.findById(row.id());
        if (existing.isEmpty()) {
            importState(row, List.of());
            return;
        }
        VacancyState old = existing.get();
        // A column the file does not have says nothing about the vacancy: its stored value stays.
        boolean url = columns.contains("url");
        VacancyStatus status = columns.contains("status") ? row.status() : old.status();
        VacancyState merged = new VacancyState(row.id(), row.title(), row.company(),
                url ? row.url() : old.url(), url ? row.canonicalUrl() : old.canonicalUrl(),
                pick(columns, "location", row.location(), old.location()), pick(columns, "country", row.country(), old.country()),
                pick(columns, "work_mode", row.workMode(), old.workMode()), old.description(),
                pick(columns, "salary_min", row.salaryMin(), old.salaryMin()), pick(columns, "salary_max", row.salaryMax(), old.salaryMax()),
                pick(columns, "currency", row.currency(), old.currency()), pick(columns, "salary_period", row.salaryPeriod(), old.salaryPeriod()),
                status, pick(columns, "priority", row.priority(), old.priority()), pick(columns, "notes", row.notes(), old.notes()),
                pick(columns, "contact", row.contact(), old.contact()), pick(columns, "next_action_note", row.nextActionNote(), old.nextActionNote()),
                pick(columns, "next_action_at", row.nextActionAt(), old.nextActionAt()), pick(columns, "tags", row.tags(), old.tags()),
                old.userEditedFields(), pick(columns, "first_seen_at", row.firstSeenAt(), old.firstSeenAt()),
                pick(columns, "last_seen_at", row.lastSeenAt(), old.lastSeenAt()), old.isNew());
        repository.save(merged);
        if (old.status() != status) {
            repository.appendStatusEvent(new StatusEvent(row.id(), old.status(), status, clock.instant(), "imported from CSV"));
        }
    }

    private static <T> T pick(java.util.Set<String> columns, String column, T fromFile, T stored) {
        return columns.contains(column) ? fromFile : stored;
    }

    @Transactional(readOnly = true)
    public List<StatusEvent> history(UUID id) {
        get(id);
        return repository.findHistory(id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Partial update. A null field is left unchanged; an empty text field clears it. */
    public record UpdateCommand(
            String description,
            String notes,
            String contact,
            Integer priority,
            List<String> tags,
            Instant nextActionAt,
            String nextActionNote) {
    }
}
