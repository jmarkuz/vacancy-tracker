package com.vacancytracker.application.resume;

import com.vacancytracker.collection.normalize.CountryDetector;
import java.time.DayOfWeek;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds skill-like terms in free text (T-080, ADR-040). The code holds no list of technologies or roles: a term is
 * recognised by its shape (C#, C++, Node.js, CI/CD, PostgreSQL, AWS, S3) or by being a capitalised word inside an
 * enumeration or bullet line but not at its start ("Experience with Python, Django and Kafka"). A small list of
 * common words, month and day names and country names is left out. The result is a proposal: the user edits the
 * keyword list of the resume, and that list is what the fit score uses.
 */
public final class Terms {

    private static final Pattern TOKEN = Pattern.compile("\\.?[\\p{L}\\p{N}](?:[\\p{L}\\p{N}+#]|[./-](?=[\\p{L}\\p{N}]))*");
    private static final Pattern SKILLS_HEADING = Pattern.compile(
            "(?iu)^\\s*(?:technical\\s+|key\\s+|core\\s+|hard\\s+)?(?:skills|technologies|tech\\s+stack|stack|tools|competencies"
                    + "|навички|ключові\\s+навички|технології|навыки|технологии|umiejętności|dovednosti|kenntnisse)\\b[^\\n]{0,40}$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•·▪◦‣–—]|\\d+[.)])\\s+");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?:;]\\s*$");
    private static final Set<String> STOP = stopWords();
    private static final int MAX_TERM_LENGTH = 40;

    private Terms() {
    }

    /** The case-insensitive key two spellings of a term share. */
    public static String key(String term) {
        return term.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Keywords proposed for a resume: the items of a skills section first, then the skill-like terms of the whole
     * text by how often they occur. Distinct by {@link #key}, at most {@code limit}.
     */
    public static List<String> resumeKeywords(String text, int limit) {
        Map<String, String> picked = new LinkedHashMap<>();
        for (String item : skillsSectionItems(text)) {
            picked.putIfAbsent(key(item), item);
        }
        for (String term : skillLikeTerms(text)) {
            picked.putIfAbsent(key(term), term);
        }
        return picked.values().stream().limit(limit).toList();
    }

    /** Skill-like terms of the text, most frequent first, distinct by {@link #key}. */
    public static List<String> skillLikeTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> spelling = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            boolean listLike = BULLET.matcher(line).find() || line.chars().filter(c -> c == ',').count() >= 2;
            Matcher m = TOKEN.matcher(line);
            boolean sentenceStart = true;
            int previousEnd = 0;
            boolean first = true;
            List<String> phrase = new ArrayList<>();
            while (m.find()) {
                String gap = line.substring(previousEnd, m.start());
                if (!first && SENTENCE_END.matcher(gap).find()) {
                    sentenceStart = true;
                }
                String token = trimTrailingDots(m.group());
                boolean strong = strongShape(token);
                boolean listed = listLike && !sentenceStart && !first && capitalised(token);
                // "Spring Boot", ".NET Core": a capitalised word right after a term continues it.
                boolean continues = !phrase.isEmpty() && " ".equals(gap) && capitalised(token) && phrase.size() < 3;
                if (strong || listed || continues) {
                    if (!phrase.isEmpty() && !" ".equals(gap)) {
                        count(phrase, counts, spelling);
                    }
                    phrase.add(token);
                } else {
                    count(phrase, counts, spelling);
                }
                previousEnd = m.end();
                sentenceStart = false;
                first = false;
            }
            count(phrase, counts, spelling);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(e -> spelling.get(e.getKey()))
                .toList();
    }

    /** Counts the collected words as one term when acceptable, and empties the list. */
    private static void count(List<String> phrase, Map<String, Integer> counts, Map<String, String> spelling) {
        if (phrase.isEmpty()) {
            return;
        }
        String term = String.join(" ", phrase);
        phrase.clear();
        if (acceptable(term)) {
            String k = key(term);
            counts.merge(k, 1, Integer::sum);
            spelling.putIfAbsent(k, term);
        }
    }

    /** Whether the text mentions the term as a whole word or phrase, ignoring case ("node.js" also finds "NodeJS"). */
    public static boolean mentions(String text, String term) {
        if (text == null || term == null || term.isBlank()) {
            return false;
        }
        if (phrase(term).matcher(text).find()) {
            return true;
        }
        String compact = term.replace(".", "").replace(" ", "");
        return !compact.equalsIgnoreCase(term) && compact.length() >= 2 && phrase(compact).matcher(text).find();
    }

    private static Pattern phrase(String term) {
        String[] words = term.strip().split("\\s+");
        StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                regex.append("[\\s-]+");
            }
            regex.append(Pattern.quote(words[i]));
        }
        regex.append("(?![\\p{L}\\p{N}+#])");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Items listed under a "Skills" (or similar) heading, up to the next blank line after the first items. */
    static List<String> skillsSectionItems(String text) {
        List<String> items = new ArrayList<>();
        if (text == null) {
            return items;
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!SKILLS_HEADING.matcher(lines[i]).matches()) {
                continue;
            }
            String inline = lines[i].contains(":") ? lines[i].substring(lines[i].indexOf(':') + 1) : "";
            collectItems(inline, items);
            for (int j = i + 1; j < lines.length && j <= i + 25; j++) {
                if (lines[j].isBlank()) {
                    if (!items.isEmpty()) {
                        break;
                    }
                    continue;
                }
                String line = BULLET.matcher(lines[j]).replaceFirst("");
                collectItems(line.contains(":") ? line.substring(line.indexOf(':') + 1) : line, items);
            }
        }
        return items;
    }

    private static void collectItems(String line, List<String> into) {
        for (String raw : line.split("[,;•·|/]|\\s[-–—]\\s|\\s(?:and|та|і|и)\\s")) {
            String item = trimTrailingDots(unwrap(raw.strip()));
            int words = item.isEmpty() ? 0 : item.split("\\s+").length;
            if (words >= 1 && words <= 4 && item.length() <= MAX_TERM_LENGTH && acceptable(item)) {
                into.add(item);
            }
        }
    }

    /** C#, C++, Node.js, CI/CD, PostgreSQL, iOS, AWS, S3, K8s: shapes that plain words do not have. */
    static boolean strongShape(String token) {
        if (token.length() < 2 || token.length() > MAX_TERM_LENGTH) {
            return false;
        }
        boolean hasLetter = token.chars().anyMatch(Character::isLetter);
        if (!hasLetter) {
            return false;
        }
        if (token.contains("+") || token.contains("#")) {
            return true;
        }
        if (token.matches(".*\\p{L}[./]\\p{L}.*") || token.startsWith(".")) {
            return true;
        }
        boolean digits = token.chars().anyMatch(Character::isDigit);
        if (digits) {
            return token.length() <= 10;
        }
        if (token.length() <= 6 && token.equals(token.toUpperCase(Locale.ROOT)) && token.chars().allMatch(Character::isLetter)) {
            return true;
        }
        return token.substring(1).chars().anyMatch(Character::isUpperCase) && token.chars().anyMatch(Character::isLowerCase);
    }

    private static boolean capitalised(String token) {
        return token.length() >= 2 && Character.isUpperCase(token.charAt(0)) && token.chars().allMatch(c -> Character.isLetter(c) || c == '-');
    }

    private static boolean acceptable(String term) {
        String k = key(term);
        if (k.length() < 2 && !k.matches("[a-z][+#]+")) {
            return false;
        }
        if (k.matches("[\\d.,+%-]+") || STOP.contains(k)) {
            return false;
        }
        return CountryDetector.detect(term).isEmpty();
    }

    /** Drops a bracket left over from splitting ("(Java" or "Kafka)") but keeps a balanced one ("English (C1)"). */
    private static String unwrap(String item) {
        String t = item;
        if ((t.startsWith("(") || t.startsWith("[")) && !t.contains(")") && !t.contains("]")) {
            t = t.substring(1);
        }
        if ((t.endsWith(")") || t.endsWith("]")) && !t.contains("(") && !t.contains("[")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.strip();
    }

    private static String trimTrailingDots(String token) {
        String t = token;
        while (t.endsWith(".") || t.endsWith("-")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static Set<String> stopWords() {
        Set<String> words = new HashSet<>(List.of(
                "it", "cv", "eu", "llc", "ltd", "inc", "gmbh", "faq", "ok", "etc", "eg", "ie", "vs", "am", "pm",
                "the", "and", "or", "for", "with", "our", "we", "you", "your", "they", "this", "that", "who", "what",
                "about", "experience", "education", "skills", "summary", "profile", "responsibilities", "requirements",
                "duties", "achievements", "projects", "project", "company", "companies", "team", "work", "job", "role",
                "position", "apply", "please", "must", "nice", "have", "plus", "strong", "good", "excellent",
                "knowledge", "understanding", "ability", "familiarity", "bachelor", "master", "degree", "university",
                "present", "now", "today", "current", "remote", "office", "hybrid", "full-time", "part-time",
                "location", "salary", "benefits", "senior", "junior", "middle", "lead", "head", "intern", "years",
                "year", "month", "months", "contact", "email", "phone", "linkedin", "github", "references",
                "досвід", "освіта", "навички", "вимоги", "обов'язки", "проєкт", "проект", "компанія", "команда",
                "робота", "посада", "зарплата", "років", "роки", "рік", "опыт", "образование", "навыки", "требования"));
        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("uk"), Locale.of("ru"), Locale.of("pl"), Locale.GERMAN)) {
            for (Month month : Month.values()) {
                for (TextStyle style : List.of(TextStyle.FULL, TextStyle.SHORT, TextStyle.FULL_STANDALONE, TextStyle.SHORT_STANDALONE)) {
                    words.add(key(trimTrailingDots(month.getDisplayName(style, locale))));
                }
            }
            for (DayOfWeek day : DayOfWeek.values()) {
                words.add(key(day.getDisplayName(TextStyle.FULL, locale)));
            }
        }
        return Set.copyOf(words);
    }
}
