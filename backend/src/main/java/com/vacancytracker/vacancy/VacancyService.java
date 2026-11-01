package com.vacancytracker.vacancy;

import com.vacancytracker.collection.CanonicalUrl;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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

    @Transactional(readOnly = true)
    public Vacancy get(UUID id) {
        return repository.findById(id).map(Vacancy::restore).orElseThrow(() -> new VacancyNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Vacancy> list(VacancyStatus status, int page, int size) {
        return repository.findPage(status == null ? null : status.label(), size, page * size)
                .stream()
                .map(Vacancy::restore)
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(VacancyStatus status) {
        return repository.count(status == null ? null : status.label());
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
