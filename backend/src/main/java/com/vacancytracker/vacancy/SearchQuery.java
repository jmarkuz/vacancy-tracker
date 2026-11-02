package com.vacancytracker.vacancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what the user typed into a PostgreSQL tsquery string. Only letters and digits survive, so no input can
 * inject tsquery syntax. Every word is a prefix ("kot" finds "kotlin"), and all words must match.
 */
public final class SearchQuery {

    static final int MAX_TERMS = 8;
    static final int MAX_TERM_LENGTH = 40;
    /**
     * A word, or words joined by dots. PostgreSQL's parser keeps "node.js" as one token, so such a term also matches
     * as the whole token, next to its parts.
     */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:\\.[\\p{L}\\p{N}]+)*");

    private SearchQuery() {
    }

    /** Empty when the text has no searchable word. */
    public static Optional<String> toTsQuery(String text) {
        if (text == null) {
            return Optional.empty();
        }
        List<String> terms = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find() && terms.size() < MAX_TERMS) {
            String word = m.group();
            word = word.substring(0, Math.min(word.length(), MAX_TERM_LENGTH));
            terms.add(word.contains(".") ? dotted(word) : word + ":*");
        }
        return terms.isEmpty() ? Optional.empty() : Optional.of(String.join(" & ", terms));
    }

    private static String dotted(String word) {
        List<String> parts = new ArrayList<>();
        for (String part : word.split("\\.")) {
            if (!part.isEmpty()) {
                parts.add(part + ":*");
            }
        }
        return "('" + word + "':* | (" + String.join(" & ", parts) + "))";
    }
}
