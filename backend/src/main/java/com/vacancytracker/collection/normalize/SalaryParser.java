package com.vacancytracker.collection.normalize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a salary written as free text, such as "€70–85k / yr gross" or "PLN 18–22k / mo" (T-041).
 * Currency is taken from a symbol or an upper-case ISO code; period from common words.
 * Gross and net are not interpreted: the text is read as stated.
 */
public final class SalaryParser {

    /** Parsed salary. currency or period may be null when the text does not state them. */
    public record Salary(BigDecimal min, BigDecimal max, String currency, String period) {
    }

    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})+|\\d+(?:[.,]\\d+)?)\\s?(k)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("\\b([A-Z]{3})\\b");

    private SalaryParser() {
    }

    public static Optional<Salary> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<BigDecimal> numbers = new ArrayList<>();
        List<Boolean> thousands = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            numbers.add(toNumber(m.group(1)));
            thousands.add(m.group(2) != null);
        }
        if (numbers.isEmpty()) {
            return Optional.empty();
        }
        // "70–85k": the k applies to the first number too.
        if (numbers.size() > 1 && thousands.get(numbers.size() - 1) && !thousands.get(0)
                && numbers.get(0).compareTo(BigDecimal.valueOf(1000)) < 0) {
            numbers.set(0, numbers.get(0).multiply(BigDecimal.valueOf(1000)));
        }
        for (int i = 0; i < numbers.size(); i++) {
            if (thousands.get(i)) {
                numbers.set(i, numbers.get(i).multiply(BigDecimal.valueOf(1000)));
            }
        }
        BigDecimal min = numbers.get(0);
        BigDecimal max = numbers.size() > 1 ? numbers.get(1) : min;
        if (min.compareTo(max) > 0) {
            BigDecimal swap = min;
            min = max;
            max = swap;
        }
        return Optional.of(new Salary(min, max, currency(text), period(text)));
    }

    static String currency(String text) {
        if (text.contains("€")) {
            return "EUR";
        }
        if (text.contains("$")) {
            return "USD";
        }
        if (text.contains("£")) {
            return "GBP";
        }
        Matcher code = CODE.matcher(text);
        return code.find() ? code.group(1) : null;
    }

    static String period(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*\\b(year|yr|annum|annual|yearly)\\b.*") || lower.contains("a year") || lower.contains("per year")) {
            return "year";
        }
        if (lower.matches("(?s).*\\b(month|mo|monthly)\\b.*") || lower.contains("per month") || lower.contains("a month")) {
            return "month";
        }
        if (lower.matches("(?s).*\\b(hour|hr|hourly)\\b.*") || lower.contains("per hour") || lower.contains("an hour")) {
            return "hour";
        }
        return null;
    }

    private static BigDecimal toNumber(String raw) {
        String value = raw;
        if (value.matches("\\d{1,3}([.,]\\d{3})+")) {
            value = value.replaceAll("[.,]", "");
        } else {
            value = value.replace(',', '.');
        }
        return new BigDecimal(value);
    }
}
