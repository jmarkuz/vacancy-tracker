package com.vacancytracker.collection.adapter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A posting as a source describes it, before normalization (T-041). Every adapter maps its own
 * payload into this shape; the pipeline never sees source-specific JSON.
 */
public record RawPosting(
        String adapter,
        String externalId,
        String url,
        String title,
        String company,
        String location,
        String workMode,
        String description,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String salaryPeriod,
        Instant postedAt) {
}
