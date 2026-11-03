package com.vacancytracker.application.documents;

import com.vacancytracker.application.resume.Terms;
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
 * The deterministic fact check of a generated document against its base draft (T-098, ADR-051): numbers and
 * percentages, dates, companies, job titles and technologies that the generated text has and the base documents do
 * not. It never calls AI. The vacancy's own company and title are not reported: a letter may name its addressee.
 */
public final class FactCheck {

    public static final String NUMBER = "number";
    public static final String DATE = "date";
    public static final String COMPANY = "company";
    public static final String TITLE = "title";
    public static final String TECHNOLOGY = "technology";

    /** One fact the base documents do not contain, with the line it is on. */
    public record Finding(String category, String value, String line) {
    }

    private static final String MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?"
            + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|січ\\p{L}*|лют\\p{L}*|бер\\p{L}*|квіт\\p{L}*|трав\\p{L}*"
            + "|черв\\p{L}*|лип\\p{L}*|серп\\p{L}*|вер\\p{L}*|жовт\\p{L}*|лист\\p{L}*|груд\\p{L}*)";
    /** A year, optionally after a month name, or a numeric month/year. */
    private static final Pattern DATES = Pattern.compile(
            "(?iu)\\b(?:" + MONTH + "\\.?\\s+)?(?:19[5-9]\\d|20\\d\\d)\\b|\\b(?:0?[1-9]|1[0-2])/(?:19|20)\\d\\d\\b");
    private static final Pattern YEAR = Pattern.compile("(?:19[5-9]\\d|20\\d\\d)");
    /** A number with an optional currency, percent, "k", "m" or "+". */
    private static final Pattern NUMBERS = Pattern.compile("(?u)(?<![\\p{L}\\d.,/])[$€£₴]?\\d+(?:[.,]\\d+)*(?:\\s?%|[kKmM]\\b|\\+)?");
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern THOUSANDS = Pattern.compile("[.,](?=\\d{3}(?!\\d))");
    /** "### Title, Company, City" in a Markdown resume. */
    private static final Pattern ROLE_LINE = Pattern.compile("^#{2,4}\\s+(.+)$");
    private static final Pattern ROLE_PARTS = Pattern.compile("\\s*[,|–—]\\s*|\\s+at\\s+");
    /** "Senior Engineer at Acme Pay" in prose. */
    private static final Pattern AT = Pattern.compile(
            "(?u)\\b(?:at|в|у)\\s+([\\p{Lu}][\\p{L}\\d&.-]*(?:\\s+[\\p{Lu}][\\p{L}\\d&.-]*){0,3})");

    private FactCheck() {
    }

    /** What the generated text states that the base text does not. vacancyCompany and vacancyTitle are not reported. */
    public static List<Finding> check(String base, String generated, String vacancyCompany, String vacancyTitle) {
        String source = base == null ? "" : base;
        String sourceLower = source.toLowerCase(Locale.ROOT);
        Set<String> sourceNumbers = numbersOf(source);
        String target = ((vacancyCompany == null ? "" : vacancyCompany) + " " + (vacancyTitle == null ? "" : vacancyTitle))
                .toLowerCase(Locale.ROOT);
        Map<String, Finding> found = new LinkedHashMap<>();
        for (String line : generated.split("\n")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            List<int[]> dateSpans = new ArrayList<>();
            Matcher date = DATES.matcher(text);
            while (date.find()) {
                dateSpans.add(new int[] {date.start(), date.end()});
                Matcher year = YEAR.matcher(date.group());
                if (year.find() && !source.contains(year.group())) {
                    add(found, DATE, date.group().strip(), text);
                }
            }
            Matcher number = NUMBERS.matcher(text);
            while (number.find()) {
                if (inside(dateSpans, number.start())) {
                    continue;
                }
                Set<String> numbers = numbersOf(number.group());
                if (!numbers.isEmpty() && !sourceNumbers.containsAll(numbers)) {
                    add(found, NUMBER, number.group().strip(), text);
                }
            }
            Matcher role = ROLE_LINE.matcher(text);
            if (role.matches()) {
                String[] parts = ROLE_PARTS.split(role.group(1));
                checkName(found, TITLE, parts.length > 0 ? parts[0] : "", sourceLower, target, text);
                checkName(found, COMPANY, parts.length > 1 ? parts[1] : "", sourceLower, target, text);
            } else {
                Matcher at = AT.matcher(text);
                while (at.find()) {
                    checkName(found, COMPANY, at.group(1), sourceLower, target, text);
                }
            }
        }
        for (String term : Terms.skillLikeTerms(generated)) {
            String lower = term.toLowerCase(Locale.ROOT);
            if (!Terms.mentions(source, term) && !target.contains(lower) && !found.containsKey(key(COMPANY, term))) {
                add(found, TECHNOLOGY, term, lineOf(generated, term));
            }
        }
        return List.copyOf(found.values());
    }

    private static void checkName(Map<String, Finding> found, String category, String name, String sourceLower, String target,
                                  String line) {
        String clean = name.replaceAll("[*_`]", "").replaceAll("[.,;:]+$", "").strip();
        String lower = clean.toLowerCase(Locale.ROOT);
        if (clean.length() < 2 || sourceLower.contains(lower) || target.contains(lower) || !clean.matches("(?s).*\\p{Lu}.*")) {
            return;
        }
        add(found, category, clean, line);
    }

    /** The numbers of a text, normalised so that "1,200" and "1200" are one number and "2,5" is "2.5". */
    private static Set<String> numbersOf(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = DIGITS.matcher(text);
        while (m.find()) {
            out.add(THOUSANDS.matcher(m.group()).replaceAll("").replace(',', '.'));
        }
        return out;
    }

    private static boolean inside(List<int[]> spans, int at) {
        return spans.stream().anyMatch(s -> at >= s[0] && at < s[1]);
    }

    private static String lineOf(String text, String term) {
        for (String line : text.split("\n")) {
            if (Terms.mentions(line, term)) {
                return line.strip();
            }
        }
        return "";
    }

    private static void add(Map<String, Finding> found, String category, String value, String line) {
        found.putIfAbsent(key(category, value), new Finding(category, value, line.length() > 300 ? line.substring(0, 300) : line));
    }

    /** Companies and titles share one key, so a name is reported once. */
    private static String key(String category, String value) {
        return (COMPANY.equals(category) || TITLE.equals(category) ? "name" : category) + ":" + value.toLowerCase(Locale.ROOT);
    }
}
