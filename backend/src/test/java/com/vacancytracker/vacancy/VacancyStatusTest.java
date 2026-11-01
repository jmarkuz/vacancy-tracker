package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VacancyStatusTest {

    @ParameterizedTest
    @CsvSource({
            "New, Saved, true",
            "New, Applied, true",
            "Saved, Applied, true",
            "Applied, Screening, true",
            "Screening, Test task, true",
            "Offer, Accepted, true",
            "New, Offer, false",
            "Applied, New, false",
            "Accepted, Applied, false",
            "Rejected, Applied, false",
            "Hidden, New, true",
            "Dismissed, New, true"
    })
    void transitionsFollowTheTable(String from, String to, boolean allowed) {
        assertThat(VacancyStatus.fromLabel(from).canMoveTo(VacancyStatus.fromLabel(to))).isEqualTo(allowed);
    }

    @Test
    void terminalStatesAreTheBriefList() {
        assertThat(VacancyStatus.REJECTED.isTerminal()).isTrue();
        assertThat(VacancyStatus.WITHDRAWN.isTerminal()).isTrue();
        assertThat(VacancyStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(VacancyStatus.DISMISSED.isTerminal()).isTrue();
        assertThat(VacancyStatus.HIDDEN.isTerminal()).isTrue();
        assertThat(VacancyStatus.ACCEPTED.isTerminal()).isFalse();
        assertThat(VacancyStatus.APPLIED.isTerminal()).isFalse();
    }

    @Test
    void labelRoundTrip() {
        assertThat(VacancyStatus.fromLabel("Test task")).isEqualTo(VacancyStatus.TEST_TASK);
        assertThat(VacancyStatus.TEST_TASK.label()).isEqualTo("Test task");
    }
}
