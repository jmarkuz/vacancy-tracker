package com.vacancytracker.collection.normalize;

import java.math.BigDecimal;
import java.time.Instant;

/** A posting after cleanup: plain text, canonical URL, validated salary, detected country and work mode. */
public record NormalizedPosting(
        String adapter,
        String externalId,
        String url,
        String canonicalUrl,
        String title,
        String company,
        String location,
        String country,
        String workMode,
        String description,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String salaryPeriod,
        Instant postedAt,
        String board) {

    /** A posting from a source without boards. */
    public NormalizedPosting(String adapter, String externalId, String url, String canonicalUrl, String title, String company,
                             String location, String country, String workMode, String description, BigDecimal salaryMin,
                             BigDecimal salaryMax, String currency, String salaryPeriod, Instant postedAt) {
        this(adapter, externalId, url, canonicalUrl, title, company, location, country, workMode, description, salaryMin,
                salaryMax, currency, salaryPeriod, postedAt, null);
    }
}
