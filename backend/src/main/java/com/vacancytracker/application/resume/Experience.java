package com.vacancytracker.application.resume;

import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Years of experience, read from text (T-081). A resume's years are the merged length of its date ranges
 * ("2016 – 2019", "03/2020 - present") or, without ranges, the largest "N years" it states. A vacancy's are the
 * smallest "N+ years" it asks for. Both are estimates and are shown as such.
 */
public final class Experience {

    private static final String YEAR_WORDS = "(?:years?|yrs?|років|роки|року|рік|лет|года|год|lat|let|jahre?n?)";
    private static final Pattern YEARS = Pattern.compile("(?iu)(\\d{1,2})\\s*\\+?\\s*(?:-\\s*\\d{1,2}\\s*)?" + YEAR_WORDS);
    private static final Pattern RANGE = Pattern.compile(
            "(?iu)(?:\\d{1,2}[./])?((?:19|20)\\d{2})\\s*[-–—]+\\s*(?:\\d{1,2}[./])?((?:19|20)\\d{2}|present|now|current|today"
                    + "|теперішній час|дотепер|по теперішній час|зараз|наш час|настоящее время|сейчас|obecnie|heute)");
    private static final int MAX_YEARS = 50;

    private Experience() {
    }

    /** Years of experience a resume shows, or empty when it shows none. */
    public static OptionalInt ofResume(String text, Year now) {
        if (text == null) {
            return OptionalInt.empty();
        }
        List<int[]> ranges = new ArrayList<>();
        Matcher m = RANGE.matcher(text);
        while (m.find()) {
            int from = Integer.parseInt(m.group(1));
            String end = m.group(2);
            int to = end.chars().allMatch(Character::isDigit) ? Integer.parseInt(end) : now.getValue();
            if (from <= to && to <= now.getValue() && to - from <= MAX_YEARS) {
                ranges.add(new int[] {from, to});
            }
        }
        if (!ranges.isEmpty()) {
            return OptionalInt.of(merged(ranges));
        }
        return stated(text).max();
    }

    /** Smallest number of years the vacancy asks for, or empty. */
    public static OptionalInt required(String text) {
        return text == null ? OptionalInt.empty() : stated(text).min();
    }

    private static java.util.stream.IntStream stated(String text) {
        List<Integer> found = new ArrayList<>();
        Matcher m = YEARS.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n > 0 && n <= MAX_YEARS) {
                found.add(n);
            }
        }
        return found.stream().mapToInt(Integer::intValue);
    }

    /** Total length of the ranges with overlaps counted once; a range within one year counts as one. */
    private static int merged(List<int[]> ranges) {
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        int total = 0;
        int start = ranges.get(0)[0];
        int end = ranges.get(0)[1];
        for (int[] r : ranges.subList(1, ranges.size())) {
            if (r[0] <= end) {
                end = Math.max(end, r[1]);
            } else {
                total += Math.max(1, end - start);
                start = r[0];
                end = r[1];
            }
        }
        return total + Math.max(1, end - start);
    }
}
