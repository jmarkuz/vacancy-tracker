package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatusPathTest {

    @Test
    void findsTheShortestChainOfAllowedTransitions() {
        assertThat(StatusPath.between(VacancyStatus.SAVED, VacancyStatus.APPLIED)).contains(List.of(VacancyStatus.APPLIED));
        assertThat(StatusPath.between(VacancyStatus.APPLIED, VacancyStatus.INTERVIEW))
                .contains(List.of(VacancyStatus.SCREENING, VacancyStatus.INTERVIEW));
        assertThat(StatusPath.between(VacancyStatus.APPLIED, VacancyStatus.OFFER))
                .contains(List.of(VacancyStatus.SCREENING, VacancyStatus.INTERVIEW, VacancyStatus.OFFER));
        assertThat(StatusPath.between(VacancyStatus.DISMISSED, VacancyStatus.APPLIED))
                .contains(List.of(VacancyStatus.NEW, VacancyStatus.APPLIED));
    }

    @Test
    void staysPutOrGivesUp() {
        assertThat(StatusPath.between(VacancyStatus.APPLIED, VacancyStatus.APPLIED)).contains(List.of());
        assertThat(StatusPath.between(VacancyStatus.ACCEPTED, VacancyStatus.REJECTED)).isEmpty();
    }
}
