package com.vacancytracker.collection.pipeline;

import com.vacancytracker.collection.dedup.Deduplicator;
import com.vacancytracker.collection.normalize.NormalizedPosting;
import com.vacancytracker.vacancy.AutomatedFields;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores one normalized posting: a new vacancy, or a merge into the existing one (T-042, T-016).
 * Each call is its own transaction, so one bad posting cannot undo the others.
 */
@Service
public class IngestService {

    public enum Outcome { NEW, UPDATED, DUPLICATE }

    private final Deduplicator deduplicator;
    private final VacancyRepository vacancies;

    public IngestService(Deduplicator deduplicator, VacancyRepository vacancies) {
        this.deduplicator = deduplicator;
        this.vacancies = vacancies;
    }

    @Transactional
    public Outcome ingest(NormalizedPosting posting, Instant now) {
        var match = deduplicator.match(posting);
        if (match.isEmpty()) {
            Vacancy created = Vacancy.discovered(UUID.randomUUID(), posting.title(), posting.company(), posting.url(),
                    posting.canonicalUrl(), posting.description(), now);
            created.setPlacement(posting.location(), null, posting.workMode());
            created.applyAutomated(new AutomatedFields(null, posting.salaryMin(), posting.salaryMax(),
                    posting.currency(), posting.salaryPeriod(), now));
            vacancies.save(created.toState());
            vacancies.upsertSource(created.getId(), posting.adapter(), posting.externalId(), posting.url(), now);
            return Outcome.NEW;
        }

        UUID id = match.get().vacancyId();
        Vacancy existing = Vacancy.restore(vacancies.findById(id).orElseThrow());
        String before = existing.getDescription();
        BigDecimal beforeMin = existing.getSalaryMin();
        BigDecimal beforeMax = existing.getSalaryMax();
        existing.applyAutomated(new AutomatedFields(posting.description(), posting.salaryMin(), posting.salaryMax(),
                posting.currency(), posting.salaryPeriod(), now));
        if (existing.getLocation() == null && posting.location() != null) {
            existing.setPlacement(posting.location(), existing.getCountry(), existing.getWorkMode());
        }
        boolean changed = !Objects.equals(before, existing.getDescription())
                || !Objects.equals(beforeMin, existing.getSalaryMin())
                || !Objects.equals(beforeMax, existing.getSalaryMax());
        vacancies.save(existing.toState());
        vacancies.upsertSource(id, posting.adapter(), posting.externalId(), posting.url(), now);
        return changed ? Outcome.UPDATED : Outcome.DUPLICATE;
    }
}
