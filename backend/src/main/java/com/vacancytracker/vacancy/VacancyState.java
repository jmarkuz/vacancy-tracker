package com.vacancytracker.vacancy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Storage representation of a vacancy row. {@link Vacancy#restore} and {@link Vacancy#toState} convert it. */
public record VacancyState(
        UUID id,
        String title,
        String company,
        String url,
        String canonicalUrl,
        String location,
        String workMode,
        String description,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String salaryPeriod,
        VacancyStatus status,
        Integer priority,
        String notes,
        String contact,
        String nextActionNote,
        Instant nextActionAt,
        List<String> tags,
        Set<String> userEditedFields,
        Instant firstSeenAt,
        Instant lastSeenAt,
        boolean isNew) {
}
