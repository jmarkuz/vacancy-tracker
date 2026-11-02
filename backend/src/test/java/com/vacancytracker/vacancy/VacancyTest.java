package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VacancyTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-01T12:00:00Z");

    private Vacancy vacancy;

    @BeforeEach
    void setUp() {
        vacancy = Vacancy.discovered(UUID.randomUUID(), "Backend Engineer", "Northwind", "https://ex.com/1",
                "https://ex.com/1", "old description", T0);
    }

    @Test
    void discoveredVacancyStartsAsNewInInbox() {
        assertThat(vacancy.getStatus()).isEqualTo(VacancyStatus.NEW);
        assertThat(vacancy.isNew()).isTrue();
        assertThat(vacancy.getFirstSeenAt()).isEqualTo(T0);
    }

    @Test
    void discoveredRequiresTitleAndCompany() {
        assertThatThrownBy(() -> Vacancy.discovered(UUID.randomUUID(), " ", "c", null, null, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Vacancy.discovered(UUID.randomUUID(), "t", null, null, null, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeStatusRecordsEventAndClearsIsNew() {
        StatusEvent event = vacancy.changeStatus(VacancyStatus.SAVED, T1, "good fit");

        assertThat(event.from()).isEqualTo(VacancyStatus.NEW);
        assertThat(event.to()).isEqualTo(VacancyStatus.SAVED);
        assertThat(event.note()).isEqualTo("good fit");
        assertThat(vacancy.getStatus()).isEqualTo(VacancyStatus.SAVED);
        assertThat(vacancy.isNew()).isFalse();
    }

    @Test
    void invalidTransitionIsRejectedAndStatusUnchanged() {
        assertThatThrownBy(() -> vacancy.changeStatus(VacancyStatus.OFFER, T1, null))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(vacancy.getStatus()).isEqualTo(VacancyStatus.NEW);
    }

    @Test
    void automationRefreshesLastSeenAndDescriptionWhenNotEdited() {
        vacancy.applyAutomated(new AutomatedFields("new description", BigDecimal.TEN, null, "EUR", "year", T1));

        assertThat(vacancy.getLastSeenAt()).isEqualTo(T1);
        assertThat(vacancy.getDescription()).isEqualTo("new description");
        assertThat(vacancy.getSalaryMin()).isEqualByComparingTo("10");
    }

    @Test
    void automationNeverOverwritesUserEditedDescriptionOrSalary() {
        vacancy.editDescription("my notes on the text");
        vacancy.editSalary(BigDecimal.ONE, BigDecimal.TWO, "USD", "month");

        vacancy.applyAutomated(new AutomatedFields("scraped text", BigDecimal.TEN, null, "EUR", "year", T1));

        assertThat(vacancy.getDescription()).isEqualTo("my notes on the text");
        assertThat(vacancy.getSalaryMin()).isEqualByComparingTo("1");
        assertThat(vacancy.getCurrency()).isEqualTo("USD");
        assertThat(vacancy.getUserEditedFields()).containsExactlyInAnyOrder("description", "salary");
        assertThat(vacancy.getLastSeenAt()).isEqualTo(T1);
    }

    @Test
    void aPostingWithoutSalaryDoesNotEraseAKnownSalary() {
        vacancy.applyAutomated(new AutomatedFields(null, BigDecimal.ONE, BigDecimal.TEN, "EUR", "year", T1));
        vacancy.applyAutomated(new AutomatedFields(null, null, null, null, null, T1));

        assertThat(vacancy.getSalaryMax()).isEqualByComparingTo("10");
        assertThat(vacancy.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void automationDoesNotMoveLastSeenBackwards() {
        vacancy.applyAutomated(new AutomatedFields(null, null, null, null, null, Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(vacancy.getLastSeenAt()).isEqualTo(T0);
    }

    @Test
    void userFieldsSurviveAutomation() {
        vacancy.changeStatus(VacancyStatus.SAVED, T1, null);
        vacancy.setNotes("keep me");
        vacancy.setContact("recruiter@example.org");
        vacancy.setNextAction(T1, "call back");
        vacancy.setPriority(2);
        vacancy.setTags(List.of("dream"));

        vacancy.applyAutomated(new AutomatedFields("x", BigDecimal.ONE, BigDecimal.TEN, "EUR", "year", T1));

        assertThat(vacancy.getStatus()).isEqualTo(VacancyStatus.SAVED);
        assertThat(vacancy.getNotes()).isEqualTo("keep me");
        assertThat(vacancy.getContact()).isEqualTo("recruiter@example.org");
        assertThat(vacancy.getNextActionNote()).isEqualTo("call back");
        assertThat(vacancy.getNextActionAt()).isEqualTo(T1);
        assertThat(vacancy.getPriority()).isEqualTo(2);
        assertThat(vacancy.getTags()).containsExactly("dream");
    }

    @Test
    void priorityOutOfRangeIsRejected() {
        assertThatThrownBy(() -> vacancy.setPriority(4)).isInstanceOf(IllegalArgumentException.class);
    }
}
