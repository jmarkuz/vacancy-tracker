package com.vacancytracker.collection.normalize;

import com.vacancytracker.collection.CanonicalUrl;
import com.vacancytracker.collection.adapter.RawPosting;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;

/**
 * Turns a RawPosting into a NormalizedPosting (T-041). Returns empty when the posting lacks a title or
 * company, which the pipeline reports as a rejected row. Country and seniority detection need the
 * user's profile data and are not done here yet.
 */
public final class Normalizer {

    private Normalizer() {
    }

    public static Optional<NormalizedPosting> normalize(RawPosting raw) {
        String title = singleLine(raw.title());
        String company = singleLine(raw.company());
        if (title == null || company == null) {
            return Optional.empty();
        }
        String location = singleLine(raw.location());
        String description = plainText(raw.description());
        String workMode = raw.workMode() != null
                ? normalizeWorkMode(raw.workMode())
                : detectWorkMode(location, description);

        BigDecimal min = nonNegative(raw.salaryMin());
        BigDecimal max = nonNegative(raw.salaryMax());
        if (min != null && max != null && min.compareTo(max) > 0) {
            BigDecimal swap = min;
            min = max;
            max = swap;
        }
        String currency = raw.currency() == null ? null : raw.currency().trim().toUpperCase(Locale.ROOT);
        // A salary without a currency cannot be compared (ADR-011), so it is dropped.
        boolean hasSalary = currency != null && (min != null || max != null);

        return Optional.of(new NormalizedPosting(
                raw.adapter(),
                blankToNull(raw.externalId()),
                blankToNull(raw.url()),
                CanonicalUrl.of(raw.url()).orElse(null),
                title,
                company,
                location,
                workMode,
                description,
                hasSalary ? (min != null ? min : max) : null,
                hasSalary ? (max != null ? max : min) : null,
                hasSalary ? currency : null,
                hasSalary ? raw.salaryPeriod() : null,
                raw.postedAt()));
    }

    /** HTML to plain text, whitespace collapsed; blank becomes null. */
    static String plainText(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = Jsoup.parse(html).text().replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    static String singleLine(String value) {
        if (value == null) {
            return null;
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    static String normalizeWorkMode(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("hybrid")) {
            return "hybrid";
        }
        if (lower.contains("remote")) {
            return "remote";
        }
        if (lower.contains("onsite") || lower.contains("on-site") || lower.contains("on site")) {
            return "onsite";
        }
        return detectWorkMode(value, null);
    }

    /** Looks for work-mode words in the location and description. Hybrid is checked first. */
    static String detectWorkMode(String location, String description) {
        String text = ((location == null ? "" : location) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        if (text.contains("hybrid")) {
            return "hybrid";
        }
        if (text.contains("remote")) {
            return "remote";
        }
        if (text.contains("onsite") || text.contains("on-site") || text.contains("on site")) {
            return "onsite";
        }
        return null;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
