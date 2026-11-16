package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
    void everyKeyPutsMissingValuesLastAndBreaksTiesByIdInBothDirections() {
        for (String key : List.of("title", "company", "location", "country", "workMode", "status", "priority", "salaryMax",
                "matchScore", "visaSponsorship", "firstSeenAt", "lastSeenAt", "postedAt", "nextActionAt")) {
            assertThat(VacancySort.parse(key + ":asc").orderBy()).endsWith(" ASC NULLS LAST, id");
            assertThat(VacancySort.parse(key + ":DESC").orderBy()).endsWith(" DESC NULLS LAST, id");
        }
    }

    @Test
    void salarySortsByTheHighestAmountStatedOrTheLowestWhenOnlyThatIsGiven() {
        assertThat(VacancySort.parse("salaryMax:desc").orderBy()).startsWith("COALESCE(salary_max, salary_min) DESC");
    }

    @Test
    void statusSortsInPipelineOrder() {
        String order = VacancySort.parse("status").orderBy();
        assertThat(order.indexOf("'New'")).isLessThan(order.indexOf("'Applied'"));
        assertThat(order.indexOf("'Offer'")).isLessThan(order.indexOf("'Hidden'"));
        for (VacancyStatus status : VacancyStatus.values()) {
            assertThat(order).contains("WHEN '" + status.label() + "' THEN");
        }
    }

    @Test
    void anUnknownFieldOrDirectionIsRejected() {
        assertThatThrownBy(() -> VacancySort.parse("password:asc")).hasMessageContaining("Unknown sort field");
        assertThatThrownBy(() -> VacancySort.parse("postedAt:sideways")).hasMessageContaining("direction");
    }
}
