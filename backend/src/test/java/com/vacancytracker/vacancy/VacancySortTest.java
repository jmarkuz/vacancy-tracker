package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VacancySortTest {

    @Test
    void theDefaultSortsByWhenItWasLastSeenNewestFirst() {
        assertThat(VacancySort.parse(null).orderBy()).contains("last_seen_at DESC");
    }

    @Test
    void theAgeOfThePostingCanBeSortedOnAndUndatedVacanciesGoLast() {
        assertThat(VacancySort.parse("postedAt:desc").orderBy()).isEqualTo("posted_at DESC NULLS LAST, id");
        assertThat(VacancySort.parse("postedAt:asc").orderBy()).isEqualTo("posted_at ASC NULLS LAST, id");
        assertThat(VacancySort.parse("postedAt").orderBy()).isEqualTo("posted_at ASC NULLS LAST, id");
    }

    @Test
    void anUnknownFieldOrDirectionIsRejected() {
        assertThatThrownBy(() -> VacancySort.parse("password:asc")).hasMessageContaining("Unknown sort field");
        assertThatThrownBy(() -> VacancySort.parse("postedAt:sideways")).hasMessageContaining("direction");
    }
}
