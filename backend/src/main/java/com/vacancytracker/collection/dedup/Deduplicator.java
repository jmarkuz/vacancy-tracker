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
        String title = words(posting.title());
        String location = words(posting.location());
        Candidate best = null;
        double bestScore = 0;
        for (Candidate candidate : vacancies.findByCompany(posting.company(), CANDIDATE_LIMIT)) {
            if (!sameLocation(location, words(candidate.location()))) {
                continue;
            }
            // Two ids on one board are two openings, whatever their titles say (review 2026-11-02).
            if (posting.externalId() != null && vacancies.hasOtherSourceId(candidate.id(), posting.adapter(), posting.externalId())) {
                continue;
            }
            double score = titleScore(title, words(candidate.title()));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= FUZZY_THRESHOLD ? Optional.of(new Match(best.id(), Rule.FUZZY_KEY)) : Optional.empty();
    }

    /** Two places agree when either is unknown, they are equal, or one is the other with more detail ("Berlin" / "Berlin Germany"). */
    static boolean sameLocation(String a, String b) {
        return a.isEmpty() || b.isEmpty() || a.equals(b) || (" " + a + " ").contains(" " + b + " ") || (" " + b + " ").contains(" " + a + " ");
    }

    /**
     * 1.0 for the same title once punctuation and "(m/f/d)" are ignored. Otherwise the titles must have the same words
     * except one that differs by a typo (at most two edits, six letters or more); "Engineer I" / "Engineer II" or
     * "(Java)" / "(Go)" are different roles and score 0. Jaro-Winkler alone scores those pairs above 0.93.
     */
    double titleScore(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        String[] x = a.split(" ");
        String[] y = b.split(" ");
        if (x.length != y.length) {
            return 0;
        }
        int differing = 0;
        for (int i = 0; i < x.length; i++) {
            if (!x[i].equals(y[i])) {
                differing++;
                boolean typo = x[i].length() >= 6 && y[i].length() >= 6 && !x[i].matches(".*\\d.*") && !y[i].matches(".*\\d.*")
                        && similarity.apply(x[i], y[i]) >= FUZZY_THRESHOLD && editDistanceAtMostTwo(x[i], y[i]);
                if (!typo) {
                    return 0;
                }
            }
        }
        return differing == 1 ? FUZZY_THRESHOLD : 0;
    }

    private static boolean editDistanceAtMostTwo(String a, String b) {
        return new org.apache.commons.text.similarity.LevenshteinDistance(2).apply(a, b) >= 0;
    }

    /** Lower case, "(m/f/d)" style suffixes removed, anything but letters and digits becomes a single space. */
    static String words(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("\\(\\s*[a-z]\\s*/\\s*[a-z](\\s*/\\s*[a-z])?\\s*\\)", " ")
                .replaceAll("[^\\p{L}\\p{N}+#]+", " ").trim();
    }
}
