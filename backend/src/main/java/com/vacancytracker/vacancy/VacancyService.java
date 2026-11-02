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

    /** History note of a vacancy the user added. It is not automatic, so automation never hides it (ADR-026). */
    public static final String ADDED_BY_HAND_NOTE = "added by hand";

    @Transactional
    public Vacancy create(String title, String company, String url, String description) {
        Instant now = clock.instant();
        String link = CanonicalUrl.webLink(url);
        if (blankToNull(url) != null && link == null) {
            throw new IllegalArgumentException("The link must be an http:// or https:// address");
        }
        String canonical = CanonicalUrl.of(link).orElse(null);
        Vacancy vacancy = Vacancy.discovered(UUID.randomUUID(), title, company, link, canonical, description, now);
        repository.save(vacancy.toState());
        // A source that later lists the same posting must not make it look automatic (review 2026-11-02).
        repository.appendStatusEvent(new StatusEvent(vacancy.getId(), null, VacancyStatus.NEW, now, ADDED_BY_HAND_NOTE));
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

    /**
     * The same list in an order that a running collection cannot change (by first sighting, not last), for exports
     * that read it in several pages.
     */
    @Transactional(readOnly = true)
    public List<Vacancy> listStable(VacancyStatus status, int page, int size) {
        return search(statusFilter(status), new VacancySort("first_seen_at", false), page, size);
    }

    @Transactional(readOnly = true)
    public long count(VacancyStatus status) {
        return countMatching(statusFilter(status));
    }

    /** Vacancies matching a compiled filter, in the given order. Page is zero-based. */
    @Transactional(readOnly = true)
    public List<Vacancy> search(SqlFragment where, VacancySort sort, int page, int size) {
        long offset = (long) page * size;
        if (page < 0 || size < 1 || offset > 10_000_000L) {
            throw new IllegalArgumentException("The page is out of range");
        }
        return repository.findPage(where, sort, size, (int) offset).stream().map(Vacancy::restore).toList();
    }

    @Transactional(readOnly = true)
    public long countMatching(SqlFragment where) {
        return repository.count(where);
    }

    private static SqlFragment statusFilter(VacancyStatus status) {
        return status == null ? SqlFragment.ALL
                : new SqlFragment("status = :status", Map.of("status", status.label()));
    }

    /** The vacancy, locked until the transaction ends, for a read-modify-write. */
    private Vacancy lockForChange(UUID id) {
        return repository.findByIdForUpdate(id).map(Vacancy::restore).orElseThrow(() -> new VacancyNotFoundException(id));
    }

    @Transactional
    public Vacancy update(UUID id, UpdateCommand cmd) {
        Vacancy vacancy = lockForChange(id);
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
        // Each part is its own field: a PATCH with only the note keeps the date, and the other way round.
        if (cmd.nextActionAt() != null || cmd.nextActionNote() != null) {
            vacancy.setNextAction(cmd.nextActionAt() != null ? cmd.nextActionAt() : vacancy.getNextActionAt(),
                    cmd.nextActionNote() != null ? cmd.nextActionNote() : vacancy.getNextActionNote());
        }
        repository.save(vacancy.toState());
        return vacancy;
    }

    @Transactional
    public Vacancy changeStatus(UUID id, VacancyStatus to, String note) {
        Vacancy vacancy = lockForChange(id);
        StatusEvent event = vacancy.changeStatus(to, clock.instant(), note);
        repository.save(vacancy.toState());
        repository.appendStatusEvent(event);
        return vacancy;
    }

    /**
     * Moves the vacancy to the target status through the shortest chain of allowed transitions (StatusPath), each step
     * a normal status event with the note. Already there: nothing changes. Unreachable (Accepted, for example): the
     * same exception as a single invalid step.
     */
    @Transactional
    public Vacancy moveTo(UUID id, VacancyStatus target, String note) {
        Vacancy vacancy = lockForChange(id);
        java.util.List<VacancyStatus> path = StatusPath.between(vacancy.getStatus(), target)
                .orElseThrow(() -> new InvalidStatusTransitionException(vacancy.getStatus(), target));
        // One microsecond apart (the database's resolution), so the history keeps the steps in their order.
        java.time.Instant at = clock.instant();
        for (int i = 0; i < path.size(); i++) {
            StatusEvent event = vacancy.changeStatus(path.get(i), at.plusNanos(1_000L * i), note);
            repository.appendStatusEvent(event);
        }
        if (!path.isEmpty()) {
            repository.save(vacancy.toState());
        }
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
                pick(columns, "work_mode", row.workMode(), old.workMode()), pick(columns, "description", row.description(), old.description()),
                pick(columns, "salary_min", row.salaryMin(), old.salaryMin()), pick(columns, "salary_max", row.salaryMax(), old.salaryMax()),
                pick(columns, "currency", row.currency(), old.currency()), pick(columns, "salary_period", row.salaryPeriod(), old.salaryPeriod()),
                status, pick(columns, "priority", row.priority(), old.priority()), pick(columns, "notes", row.notes(), old.notes()),
                pick(columns, "contact", row.contact(), old.contact()), pick(columns, "next_action_note", row.nextActionNote(), old.nextActionNote()),
                pick(columns, "next_action_at", row.nextActionAt(), old.nextActionAt()), pick(columns, "tags", row.tags(), old.tags()),
                editedFields(columns, row, old), pick(columns, "first_seen_at", row.firstSeenAt(), old.firstSeenAt()),
                pick(columns, "last_seen_at", row.lastSeenAt(), old.lastSeenAt()), old.isNew(),
                VisaSponsorship.detect(row.title(), pick(columns, "description", row.description(), old.description())));
        repository.save(merged);
        if (old.status() != status) {
            repository.appendStatusEvent(new StatusEvent(row.id(), old.status(), status, clock.instant(), "imported from CSV"));
        }
    }

    /** A description given by the file and different from the stored one is the user's: automation must keep it. */
    private static java.util.Set<String> editedFields(java.util.Set<String> columns, VacancyState row, VacancyState old) {
        java.util.Set<String> marks = new java.util.LinkedHashSet<>(old.userEditedFields());
        if (columns.contains("description") && row.description() != null && !row.description().equals(old.description())) {
            marks.add(Vacancy.FIELD_DESCRIPTION);
        }
        // A salary corrected in the file must survive the next run, as a description does (review 2026-11-02).
        boolean salaryColumns = columns.contains("salary_min") || columns.contains("salary_max")
                || columns.contains("currency") || columns.contains("salary_period");
        boolean salaryChanged = !java.util.Objects.equals(amount(row.salaryMin()), amount(old.salaryMin()))
                || !java.util.Objects.equals(amount(row.salaryMax()), amount(old.salaryMax()))
                || !java.util.Objects.equals(row.currency(), old.currency())
                || !java.util.Objects.equals(row.salaryPeriod(), old.salaryPeriod());
        if (salaryColumns && salaryChanged) {
            marks.add(Vacancy.FIELD_SALARY);
        }
        return marks;
    }

    /** 70000 and 70000.00 are the same amount. */
    private static java.math.BigDecimal amount(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
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
