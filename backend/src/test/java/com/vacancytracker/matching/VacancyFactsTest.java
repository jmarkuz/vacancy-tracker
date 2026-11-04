package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.vacancy.Vacancy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The facts a vacancy offers to the rules; here the age of the posting. */
class VacancyFactsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private static Vacancy vacancy(Instant postedAt) {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), "Backend Engineer", "Zeta", "https://ex.com/1", null, "Plain", NOW);
        v.recordPostedAt(postedAt);
        return v;
    }

    @Test
    void theAgeIsTheWholeDaysSinceThePostingDate() {
        Map<String, Object> facts = VacancyFacts.of(vacancy(Instant.parse("2026-10-25T13:00:00Z")), List.of("djinni"), NOW);

        assertThat(facts.get("postedAge")).isEqualTo(9L);
    }

    @Test
    void aVacancyPostedTodayIsZeroDaysOld() {
        assertThat(VacancyFacts.of(vacancy(Instant.parse("2026-11-04T01:00:00Z")), List.of(), NOW).get("postedAge")).isEqualTo(0L);
    }

    @Test
    void aDateInTheFutureCountsAsZeroNotNegative() {
        assertThat(VacancyFacts.of(vacancy(Instant.parse("2026-11-09T00:00:00Z")), List.of(), NOW).get("postedAge")).isEqualTo(0L);
    }

    @Test
    void withoutADateThereIsNoAgeFact() {
        assertThat(VacancyFacts.of(vacancy(null), List.of(), NOW)).doesNotContainKey("postedAge");
    }
}
