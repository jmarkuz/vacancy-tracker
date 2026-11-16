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

    /** What happened to the posting, and the vacancy it became or was merged into. */
    public record Ingested(Outcome outcome, UUID vacancyId) {
    }

    private final Deduplicator deduplicator;
    private final VacancyRepository vacancies;

    public IngestService(Deduplicator deduplicator, VacancyRepository vacancies) {
        this.deduplicator = deduplicator;
        this.vacancies = vacancies;
    }

    @Transactional
    public Ingested ingest(NormalizedPosting posting, Instant now) {
        var match = deduplicator.match(posting);
        if (match.isEmpty()) {
            Vacancy created = Vacancy.discovered(UUID.randomUUID(), posting.title(), posting.company(), posting.url(),
                    posting.canonicalUrl(), posting.description(), now);
            created.setPlacement(posting.location(), posting.country(), posting.workMode());
            created.recordPostedAt(notInTheFuture(posting.postedAt(), now));
            created.applyAutomated(new AutomatedFields(null, posting.salaryMin(), posting.salaryMax(),
                    posting.currency(), posting.salaryPeriod(), now));
            vacancies.save(created.toState());
            vacancies.upsertSource(created.getId(), posting.adapter(), posting.externalId(), posting.url(), now);
            return new Ingested(Outcome.NEW, created.getId());
        }

        UUID id = match.get().vacancyId();
        Vacancy existing = Vacancy.restore(vacancies.findByIdForUpdate(id).orElseThrow());
        String before = existing.getDescription();
        BigDecimal beforeMin = existing.getSalaryMin();
        BigDecimal beforeMax = existing.getSalaryMax();
        existing.recordPostedAt(notInTheFuture(posting.postedAt(), now));
        existing.applyAutomated(new AutomatedFields(descriptionToWrite(before, posting.description()), posting.salaryMin(),
                posting.salaryMax(), posting.currency(), posting.salaryPeriod(), now));
        // Placement the vacancy lacks is filled from this sighting; what is already there is never overwritten.
        String location = existing.getLocation() == null ? posting.location() : existing.getLocation();
        String country = existing.getCountry();
        if (country == null && posting.country() != null && Objects.equals(location, posting.location())) {
            country = posting.country();
        }
        String workMode = existing.getWorkMode() == null ? posting.workMode() : existing.getWorkMode();
        boolean placementFilled = !Objects.equals(location, existing.getLocation()) || !Objects.equals(country, existing.getCountry())
                || !Objects.equals(workMode, existing.getWorkMode());
        if (placementFilled) {
            existing.setPlacement(location, country, workMode);
        }
        boolean changed = placementFilled || !Objects.equals(before, existing.getDescription())
                || !Objects.equals(beforeMin, existing.getSalaryMin())
                || !Objects.equals(beforeMax, existing.getSalaryMax());
        vacancies.save(existing.toState());
        vacancies.upsertSource(id, posting.adapter(), posting.externalId(), posting.url(), now);
        return new Ingested(changed ? Outcome.UPDATED : Outcome.DUPLICATE, id);
    }

    /** A date after the moment of the run is a source's clock or zone error; it is read as "now". */
    private static Instant notInTheFuture(Instant postedAt, Instant now) {
        return postedAt != null && postedAt.isAfter(now) ? now : postedAt;
    }

    /**
     * What to write as the description of a vacancy seen again. A text under half the stored length is a snippet or a
     * teaser from another source (a feed, an aggregator); it must not replace the full text, so nothing is written.
     */
    private static String descriptionToWrite(String stored, String seen) {
        if (seen == null || stored != null && seen.length() * 2 < stored.length()) {
            return null;
        }
        return seen;
    }
}
