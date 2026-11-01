package com.vacancytracker.collection.dedup;

import com.vacancytracker.collection.normalize.NormalizedPosting;
import com.vacancytracker.vacancy.VacancyRepository;
import com.vacancytracker.vacancy.VacancyRepository.Candidate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

/**
 * Finds the existing vacancy a posting belongs to (ADR-013). Rules run in order and the first match wins:
 * canonical URL, then source external id, then a fuzzy match on company, title and location.
 */
@Component
public class Deduplicator {

    public enum Rule { CANONICAL_URL, SOURCE_ID, FUZZY_KEY }

    public record Match(UUID vacancyId, Rule rule) {
    }

    static final double FUZZY_THRESHOLD = 0.9;
    static final int CANDIDATE_LIMIT = 200;

    private final VacancyRepository vacancies;
    private final JaroWinklerSimilarity similarity = new JaroWinklerSimilarity();

    public Deduplicator(VacancyRepository vacancies) {
        this.vacancies = vacancies;
    }

    public Optional<Match> match(NormalizedPosting posting) {
        if (posting.canonicalUrl() != null) {
            Optional<UUID> byUrl = vacancies.findIdByCanonicalUrl(posting.canonicalUrl());
            if (byUrl.isPresent()) {
                return Optional.of(new Match(byUrl.get(), Rule.CANONICAL_URL));
            }
        }
        if (posting.externalId() != null) {
            Optional<UUID> bySource = vacancies.findIdBySource(posting.adapter(), posting.externalId());
            if (bySource.isPresent()) {
                return Optional.of(new Match(bySource.get(), Rule.SOURCE_ID));
            }
        }
        return fuzzy(posting);
    }

    private Optional<Match> fuzzy(NormalizedPosting posting) {
        String key = key(posting.title(), posting.location());
        Candidate best = null;
        double bestScore = 0;
        for (Candidate candidate : vacancies.findByCompany(posting.company(), CANDIDATE_LIMIT)) {
            double score = similarity.apply(key, key(candidate.title(), candidate.location()));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= FUZZY_THRESHOLD ? Optional.of(new Match(best.id(), Rule.FUZZY_KEY)) : Optional.empty();
    }

    static String key(String title, String location) {
        String t = title == null ? "" : title;
        String l = location == null ? "" : location;
        return (t + " | " + l).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
