package com.vacancytracker.vacancy;

import java.math.BigDecimal;

/** A posting read from a page the user imported by URL. What the page does not state is null. */
public record ImportedPosting(
        String title,
        String company,
        String location,
        String workMode,
        String description,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String salaryPeriod) {
}
