package com.vacancytracker.vacancy;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The only fields automation may write to an existing vacancy (ADR-012).
 * Description and salary are written only when the user has not edited them.
 */
public record AutomatedFields(
        String description,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String salaryPeriod,
        Instant seenAt) {
}
