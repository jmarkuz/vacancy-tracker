package com.vacancytracker.common;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

/**
 * Hides secrets in text that is logged, stored or shown (T-072, ADR-034). Two layers: known shapes (a key in a
 * query string, a bearer token, a password in a URL, a Telegram bot address, the session cookie, a Jooble key in
 * a path) are masked wherever they appear, and every secret value from the configuration is masked as exact text
 * even when it shows up in a message no pattern expects. A masked value becomes three asterisks.
 */
public final class LogMasking {

    static final String MASK = "***";
    static final int MIN_SECRET_LENGTH = 6;

    private static final List<Rule> RULES = List.of(
            new Rule("(?i)([?&;](?:api[_-]?key|apikey|app[_-]?key|app[_-]?id|key|token|access[_-]?token|secret|client[_-]?secret|password|passwd|pwd|auth|signature|sig)=)[^&\\s\"'<>)]*", "$1" + MASK),
            new Rule("(?i)(authorization[\"']?\\s*[:=]\\s*(?:bearer|basic|token)?\\s*)[^\\s,;\"']+", "$1" + MASK),
            // A credential has a digit or one of . _ ~ + / = in it; "Basic Accounting" is a job title, not a secret.
            new Rule("(?i)\\b(bearer|basic)\\s+(?=[A-Za-z0-9._~+/=-]*[0-9._~+/=])[A-Za-z0-9._~+/=-]{8,}", "$1 " + MASK),
            new Rule("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@", "$1" + MASK + "@"),
            new Rule("/bot\\d+:[A-Za-z0-9_-]+/", "/bot" + MASK + "/"),
            new Rule("(?i)(vt_token=)[^;\\s,]+", "$1" + MASK),
            new Rule("(?i)(jooble\\.org/api/)[^/?\\s\"']+", "$1" + MASK));

    private record Rule(Pattern pattern, String replacement) {
        Rule(String regex, String replacement) {
            this(Pattern.compile(regex), replacement);
        }
    }

    private static final Set<String> SECRETS = new CopyOnWriteArraySet<>();

    private LogMasking() {
    }

    /** Remembers secret values from the configuration. Blank and very short values are ignored. */
    public static void register(Iterable<String> values) {
        for (String value : values) {
            if (value != null && value.trim().length() >= MIN_SECRET_LENGTH) {
                SECRETS.add(value.trim());
            }
        }
    }

    /** Test helper: forgets all registered secrets. */
    public static void clearRegistered() {
        SECRETS.clear();
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        // Longest first, so a secret that contains another one is hidden as a whole.
        for (String secret : SECRETS.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            if (out.contains(secret)) {
                out = out.replace(secret, MASK);
            }
        }
        return out;
    }
}
