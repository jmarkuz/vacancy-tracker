package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GhostJobTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private static Instant daysAgo(long days) {
        return NOW.minus(days, ChronoUnit.DAYS);
    }

    @Test
    void aFreshPostingWithASalaryHasNoSigns() {
        GhostJob.Assessment a = GhostJob.assess(daysAgo(3), daysAgo(3), NOW, true, NOW);

        assertThat(a.signs()).isZero();
        assertThat(a.likely()).isFalse();
        assertThat(a.reasons()).isEmpty();
    }

    @Test
    void oneSignAloneIsNotEnough() {
        assertThat(GhostJob.assess(daysAgo(3), daysAgo(3), NOW, false, NOW).likely()).as("only no salary").isFalse();
        assertThat(GhostJob.assess(daysAgo(45), daysAgo(3), NOW, true, NOW).likely()).as("only old").isFalse();
        assertThat(GhostJob.assess(daysAgo(3), daysAgo(40), NOW, true, NOW).likely()).as("only listed long").isFalse();
    }

    @Test
    void oldAndWithoutASalaryIsLikelyAndTheReasonsSayWhy() {
        GhostJob.Assessment a = GhostJob.assess(daysAgo(45), daysAgo(2), NOW, false, NOW);

        assertThat(a.likely()).isTrue();
        assertThat(a.signs()).isEqualTo(2);
        assertThat(a.reasons()).containsExactly("Posted 45 days ago", "No salary stated");
    }

    @Test
    void oldAndStillListedIsLikelyEvenWithASalary() {
        GhostJob.Assessment a = GhostJob.assess(daysAgo(60), daysAgo(50), NOW, true, NOW);

        assertThat(a.likely()).isTrue();
        assertThat(a.reasons()).containsExactly("Posted 60 days ago", "Still listed 50 days after we first saw it");
    }

    @Test
    void allThreeSignsCountAsThree() {
        GhostJob.Assessment a = GhostJob.assess(daysAgo(90), daysAgo(80), NOW, false, NOW);

        assertThat(a.signs()).isEqualTo(3);
        assertThat(a.reasons()).hasSize(3);
    }

    @Test
    void theLimitIsThirtyDaysInclusive() {
        assertThat(GhostJob.assess(daysAgo(30), NOW, NOW, true, NOW).signs()).isEqualTo(1);
        assertThat(GhostJob.assess(daysAgo(29), NOW, NOW, true, NOW).signs()).isZero();
        assertThat(GhostJob.assess(null, daysAgo(30), NOW, true, NOW).signs()).isEqualTo(1);
        assertThat(GhostJob.assess(null, daysAgo(29), NOW, true, NOW).signs()).isZero();
    }

    @Test
    void aVacancyWithoutAPostingDateIsJudgedByTheOtherTwoSigns() {
        GhostJob.Assessment a = GhostJob.assess(null, daysAgo(40), NOW, false, NOW);

        assertThat(a.likely()).isTrue();
        assertThat(a.reasons()).containsExactly("Still listed 40 days after we first saw it", "No salary stated");
        assertThat(GhostJob.assess(null, NOW, NOW, false, NOW).reasons()).containsExactly("No salary stated");
    }

    @Test
    void aVacancyObjectIsReadForItsDatesAndSalary() {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), "Java Developer", "Zeta", "https://ex.com/1", null, "Plain", daysAgo(50));
        v.recordPostedAt(daysAgo(55));

        assertThat(GhostJob.assess(v, NOW).reasons()).containsExactly("Posted 55 days ago", "No salary stated");

        v.applyAutomated(new AutomatedFields(null, new java.math.BigDecimal("5000"), null, "EUR", "month", NOW));
        assertThat(GhostJob.assess(v, NOW).reasons()).doesNotContain("No salary stated");
    }
}
