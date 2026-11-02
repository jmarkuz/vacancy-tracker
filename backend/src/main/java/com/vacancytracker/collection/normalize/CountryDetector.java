package com.vacancytracker.collection.normalize;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the country in a free-text location (T-041). Only a whole comma-separated part that is a country name counts,
 * so "Berlin, Germany" gives DE, while "Remote" or "Europe" give nothing. Names come from the JDK's locale data in
 * several languages; a name that means different countries in different languages is dropped. Bare two-letter
 * codes are not read, because they clash with US state codes ("Wilmington, DE", "Indianapolis, IN"), and
 * "Georgia", "Granada" and "Jersey" are left out because they are as likely a US state or a city. When the parts name two different countries the
 * answer is nothing rather than a guess.
 */
public final class CountryDetector {

    private static final List<String> LANGUAGES = List.of("en", "de", "cs", "sk", "pl", "uk", "fr", "es", "it", "nl");
    private static final Map<String, String> ALIASES = Map.of(
            "uk", "GB", "u.k.", "GB", "great britain", "GB", "england", "GB", "scotland", "GB",
            "usa", "US", "u.s.", "US", "u.s.a.", "US", "us", "US", "czech republic", "CZ");
    private static final Set<String> EXCLUDED = Set.of("georgia", "granada", "jersey");
    private static final Map<String, String> BY_NAME = build();

    private CountryDetector() {
    }

    public static Optional<String> detect(String location) {
        if (location == null || location.isBlank()) {
            return Optional.empty();
        }
        String found = null;
        for (String part : location.split("[,;/|()\\n]|\\s[-–—]\\s")) {
            String code = BY_NAME.get(key(part));
            if (code == null) {
                continue;
            }
            if (found != null && !found.equals(code)) {
                return Optional.empty();
            }
            found = code;
        }
        return Optional.ofNullable(found);
    }

    private static Map<String, String> build() {
        Map<String, String> names = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String code : Locale.getISOCountries()) {
            for (String language : LANGUAGES) {
                String name = Locale.of("", code).getDisplayCountry(Locale.of(language));
                if (name.isBlank() || name.equals(code)) {
                    continue;
                }
                String key = key(name);
                String previous = names.putIfAbsent(key, code);
                if (previous != null && !previous.equals(code)) {
                    ambiguous.add(key);
                }
            }
        }
        names.keySet().removeAll(ambiguous);
        names.keySet().removeAll(EXCLUDED);
        names.putAll(ALIASES);
        return Map.copyOf(names);
    }

    /** Lower case, accents removed, whitespace collapsed. */
    private static String key(String text) {
        String plain = Normalizer.normalize(text.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
