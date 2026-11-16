package com.vacancytracker.collection.adapter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A posting as a source describes it, before normalization (T-041). Every adapter maps its own
 * payload into this shape; the pipeline never sees source-specific JSON. country is for a source that
 * states the country apart from the location text: an ISO 3166 code or a country name; null otherwise.
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
        Instant postedAt,
        String country) {

    /** A posting whose source states no country apart from the location text. */
    public RawPosting(String adapter, String externalId, String url, String title, String company, String location,
                      String workMode, String description, BigDecimal salaryMin, BigDecimal salaryMax, String currency,
                      String salaryPeriod, Instant postedAt) {
        this(adapter, externalId, url, title, company, location, workMode, description, salaryMin, salaryMax, currency,
                salaryPeriod, postedAt, null);
    }
}
