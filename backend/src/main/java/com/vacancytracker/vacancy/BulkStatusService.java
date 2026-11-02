package com.vacancytracker.vacancy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status changes for many vacancies at once, with a way back (T-063). A vacancy that cannot move is skipped
 * with a reason and the rest still change. Undo restores exactly what a batch changed, and only for vacancies
 * whose last change is still that batch's change, so it never overwrites a later decision of the user.
 */
@Service
public class BulkStatusService {

    public static final int MAX_BATCH = 500;
    static final String UNDO_NOTE = "undo: bulk change taken back";

    /** from and wasNew are what the vacancy had before, which undo needs. */
    public record Changed(UUID id, String from, boolean wasNew) {
    }

    public record Skipped(UUID id, String reason) {
    }

    public record Result(List<Changed> changed, List<Skipped> skipped) {
    }

    /** status is what the vacancy was before the batch; expected is what the batch set. */
    public record UndoItem(UUID id, String status, String expected, boolean wasNew) {
    }

    private final VacancyRepository repository;
    private final Clock clock;

    public BulkStatusService(VacancyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Result change(List<UUID> ids, VacancyStatus to, String note) {
        List<UUID> unique = limited(ids);
        Instant now = clock.instant();
        List<Changed> changed = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (UUID id : unique) {
            Optional<VacancyState> found = repository.findById(id);
            if (found.isEmpty()) {
                skipped.add(new Skipped(id, "Not found"));
                continue;
            }
            Vacancy vacancy = Vacancy.restore(found.get());
            VacancyStatus from = vacancy.getStatus();
            boolean wasNew = vacancy.isNew();
            try {
                StatusEvent event = vacancy.changeStatus(to, now, note);
                repository.save(vacancy.toState());
                repository.appendStatusEvent(event);
                changed.add(new Changed(id, from.label(), wasNew));
            } catch (InvalidStatusTransitionException e) {
                skipped.add(new Skipped(id, from.label() + " cannot move to " + to.label()));
            }
        }
        return new Result(changed, skipped);
    }

    @Transactional
    public Result undo(List<UndoItem> items) {
        if (items.size() > MAX_BATCH) {
            throw new IllegalArgumentException("At most " + MAX_BATCH + " vacancies per request");
        }
        Instant now = clock.instant();
        Set<UUID> seen = new LinkedHashSet<>();
        List<Changed> restored = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (UndoItem item : items) {
            if (!seen.add(item.id())) {
                continue;
            }
            VacancyStatus previous = VacancyStatus.fromLabel(item.status());
            VacancyStatus expected = VacancyStatus.fromLabel(item.expected());
            Optional<VacancyState> found = repository.findById(item.id());
            if (found.isEmpty()) {
                skipped.add(new Skipped(item.id(), "Not found"));
                continue;
            }
            List<StatusEvent> history = repository.findHistory(item.id());
            StatusEvent last = history.isEmpty() ? null : history.get(history.size() - 1);
            Vacancy vacancy = Vacancy.restore(found.get());
            boolean stillTheBatch = last != null && last.from() == previous && last.to() == expected
                    && vacancy.getStatus() == expected;
            if (!stillTheBatch) {
                skipped.add(new Skipped(item.id(), "Changed since, so it was left as it is"));
                continue;
            }
            StatusEvent event = vacancy.undoStatus(previous, expected, item.wasNew(), now, UNDO_NOTE);
            repository.save(vacancy.toState());
            repository.appendStatusEvent(event);
            restored.add(new Changed(item.id(), previous.label(), item.wasNew()));
        }
        return new Result(restored, skipped);
    }

    private static List<UUID> limited(List<UUID> ids) {
        List<UUID> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        if (unique.size() > MAX_BATCH) {
            throw new IllegalArgumentException("At most " + MAX_BATCH + " vacancies per request");
        }
        return unique;
    }
}
