package com.vacancytracker.collection.normalize;

import com.vacancytracker.common.CanonicalUrl;
import com.vacancytracker.collection.adapter.RawPosting;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/**
 * Turns a RawPosting into a NormalizedPosting (T-041). Returns empty when the posting lacks a title or
 * company, which the pipeline reports as a rejected row. The country is read from the location when it
 * names one (CountryDetector). Seniority is not detected: its levels are the user's own list (docs/search-profiles.md),
 * so a profile expresses it with title keywords (ADR-036).
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
        // A source's own field wins; when it names no mode ("unspecified") the text is read like any other posting.
        String workMode = WorkModes.fromField(raw.workMode());
        if (workMode == null) {
            workMode = WorkModes.detect(location, title, description);
        }

        BigDecimal min = nonNegative(raw.salaryMin());
        BigDecimal max = nonNegative(raw.salaryMax());
        if (min != null && max != null && min.compareTo(max) > 0) {
            BigDecimal swap = min;
            min = max;
            max = swap;
        }
        String currency = SalaryParser.isoCurrency(raw.currency());
        // A salary without a currency cannot be compared (ADR-011), so it is dropped.
        boolean hasSalary = currency != null && (min != null || max != null);

        return Optional.of(new NormalizedPosting(
                raw.adapter(),
                blankToNull(raw.externalId()),
                CanonicalUrl.webLink(raw.url()),
                CanonicalUrl.of(CanonicalUrl.webLink(raw.url())).orElse(null),
                title,
                company,
                location,
                CountryDetector.fromField(raw.country()).or(() -> CountryDetector.detect(location)).orElse(null),
                workMode,
                description,
                hasSalary ? (min != null ? min : max) : null,
                hasSalary ? (max != null ? max : min) : null,
                hasSalary ? currency : null,
                hasSalary ? period(raw.salaryPeriod()) : null,
                raw.postedAt()));
    }

    /** HTML to plain text, whitespace collapsed; blank becomes null. */
    public static String plainText(String html) {
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
        // Some feeds send titles and names HTML-escaped ("Dev &amp; Ops", "Senior &#8211; Remote").
        String decoded = value.indexOf('&') >= 0 ? Parser.unescapeEntities(value, false) : value;
        String text = decoded.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    /** Zero is how some sources say "not given" (Remote OK), and a negative amount is no salary. */
    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    /** year, month, week, day or hour; a source's wording ("yearly", "per-hour-wage") is mapped, anything else is unknown. */
    static String period(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(?:year|yearly|annual|annually|annum|yr|p\\.?a\\.?)\\b.*")) {
            return "year";
        }
        if (lower.matches(".*\\b(?:month|monthly|mo)\\b.*")) {
            return "month";
        }
        if (lower.matches(".*\\b(?:week|weekly|wk)\\b.*")) {
            return "week";
        }
        if (lower.matches(".*\\b(?:day|daily)\\b.*")) {
            return "day";
        }
        return lower.matches(".*\\b(?:hour|hourly|hr|h)\\b.*") ? "hour" : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
