package com.vacancytracker.application.mail;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the date and time of an interview, a call or a test-task deadline in the text of a mail (M5). Pure and
 * rule-based on purpose: no network, no AI, nothing but the text and the day the mail was received. It reads
 * English, Ukrainian and Russian month and weekday names, 24-hour and AM/PM times, ISO strings, "12.11.2026",
 * and "tomorrow" or "Tuesday" counted from the received day.
 *
 * <p>It is conservative: a date without a time, a date in the past, a date more than a year ahead, a night time,
 * an ambiguous "11/12/2026", or two different candidates that look equally likely give no result at all. The time
 * zone is used only when the text names one (an offset, Z, UTC/GMT+n or a common abbreviation); otherwise the time
 * is the sender's wall clock and {@link Found#zoneKnown()} is false. Mail comes from anyone, so every pattern is
 * linear and the caller cuts the text.
 */
public final class MailEventExtractor {

    /**
     * The event. With zoneKnown, start is the time in UTC; without, it is the local date-time as the mail wrote it.
     * text is the part of the mail the event was read from.
     */
    public record Found(LocalDateTime start, boolean zoneKnown, String text) {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final int MAX_CANDIDATES = 500;
    private static final int TIME_WINDOW = 45;
    private static final int ZONE_WINDOW = 30;
    private static final int CONTEXT = 100;
    private static final int MAX_TEXT = 120;
    private static final int HORIZON_DAYS = 366;
    private static final int YEARLESS_DAYS = 120;
    private static final int EARLIEST_HOUR = 6;
    private static final int NEAR = 12;

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<>();
    private static final Map<String, DayOfWeek> WEEKDAYS = new LinkedHashMap<>();
    private static final Map<String, Integer> ZONES = Map.ofEntries(Map.entry("utc", 0), Map.entry("gmt", 0), Map.entry("wet", 0),
            Map.entry("cet", 3600), Map.entry("west", 3600), Map.entry("cest", 7200), Map.entry("eet", 7200), Map.entry("eest", 10800),
            Map.entry("msk", 10800), Map.entry("est", -18000), Map.entry("edt", -14400), Map.entry("cdt", -18000),
            Map.entry("mst", -25200), Map.entry("mdt", -21600), Map.entry("pst", -28800), Map.entry("pdt", -25200));

    static {
        String[] en = {"january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"};
        String[] enShort = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        String[] uk = {"січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"};
        String[] ru = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
        for (int i = 0; i < 12; i++) {
            MONTHS.put(en[i], i + 1);
            MONTHS.put(enShort[i], i + 1);
            MONTHS.put(uk[i], i + 1);
            MONTHS.put(ru[i], i + 1);
        }
        MONTHS.put("sept", 9);
        String[][] names = {
            {"monday", "понеділок", "понедельник"}, {"tuesday", "вівторок", "вторник"}, {"wednesday", "середу", "среду"},
            {"thursday", "четвер", "четверг"}, {"friday", "п'ятницю", "п’ятницю", "пятницу"},
            {"saturday", "суботу", "субботу"}, {"sunday", "неділю", "воскресенье"}};
        for (int i = 0; i < names.length; i++) {
            for (String n : names[i]) {
                WEEKDAYS.put(n, DayOfWeek.of(i + 1));
            }
        }
        WEEKDAYS.put("tues", DayOfWeek.TUESDAY);
        WEEKDAYS.put("thurs", DayOfWeek.THURSDAY);
    }

    private static String alternatives(Set<String> words) {
        return String.join("|", words.stream().sorted((a, b) -> b.length() - a.length()).map(Pattern::quote).toList());
    }

    private static final String MONTH = "(" + alternatives(MONTHS.keySet()) + ")";
    private static final String YEAR = "(20\\d{2})";
    private static final String BOUND_BEFORE = "(?<![\\w./:-])";

    /** 2026-11-12 */
    private static final Pattern ISO = Pattern.compile(BOUND_BEFORE + "(20\\d{2})-(\\d{2})-(\\d{2})(?!\\d)", FLAGS);
    /** 12.11.2026 or 11/12/2026 */
    private static final Pattern NUMERIC = Pattern.compile(BOUND_BEFORE + "(\\d{1,2})([./])(\\d{1,2})\\2" + YEAR + "(?![\\d]|[./]\\d)", FLAGS);
    /** 12 November 2026, 12th of November, 12 листопада */
    private static final Pattern DAY_MONTH = Pattern.compile(BOUND_BEFORE + "(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of\\s+)?" + MONTH
            + "\\b\\.?(?:,?\\s*" + YEAR + "(?![\\d:.]))?", FLAGS);
    /** November 12, 2026 */
    private static final Pattern MONTH_DAY = Pattern.compile("(?<![\\p{L}\\d])" + MONTH + "\\b\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?(?![\\d:.]\\d|:)"
            + "(?:,?\\s*" + YEAR + "(?![\\d:.]))?", FLAGS);
    private static final Pattern WEEKDAY = Pattern.compile("(?<![\\p{L}\\d])(" + alternatives(WEEKDAYS.keySet()) + ")(?![\\p{L}\\d])", FLAGS);
    private static final Pattern RELATIVE = Pattern.compile("(?<![\\p{L}\\d])(day after tomorrow|tomorrow|today|післязавтра|завтра|сьогодні"
            + "|послезавтра|сегодня)(?![\\p{L}\\d])", FLAGS);

    private static final Pattern TIME = Pattern.compile("(?<![\\d:.,/-])(?:([01]?\\d|2[0-3]):([0-5]\\d)(:[0-5]\\d)?(?:\\.\\d+)?(?:\\s?([ap])\\.?m\\b\\.?)?"
            + "|(1[0-2]|[1-9])\\s?([ap])\\.?m\\b\\.?)(?![\\d:])", FLAGS);
    private static final Pattern ZONE = Pattern.compile("(?:(Z)|\\s*[(\\[]?\\s*(?:(?:UTC|GMT)\\s*([+\\-−])\\s*(\\d{1,2})(?::?(\\d{2}))?"
            + "|([+\\-−])(\\d{2}):?(\\d{2})|(" + alternatives(ZONES.keySet()) + ")))(?![\\p{L}\\d])", FLAGS);
    private static final Pattern PARAGRAPH = Pattern.compile("\\R\\s*\\R");
    private static final Pattern TOPIC = Pattern.compile("interview|\\bcall\\b|meeting|\\bmeet\\b|zoom|\\bteams\\b|webex|skype|\\bchat\\b|conversation"
            + "|deadline|\\bdue\\b|submit|співбесід|інтерв.ю|дзвін|зустріч|розмов|дедлайн|термін|созвон|встреч|собеседован|звонок", FLAGS);

    private MailEventExtractor() {
    }

    private record DatePart(LocalDate date, int start, int end) {
    }

    private record Zone(Integer offsetSeconds, int end) {
    }

    private record TimePart(LocalTime time, Integer offsetSeconds, int start, int end) {
    }

    private record Candidate(LocalDateTime start, boolean zoneKnown, String text, int score) {
    }

    /** The most likely event in the text of a mail received at receivedAt, or empty when there is none or it is unclear. */
    public static Optional<Found> extract(String text, Instant receivedAt) {
        if (text == null || text.isBlank() || receivedAt == null) {
            return Optional.empty();
        }
        LocalDateTime received = LocalDateTime.ofInstant(receivedAt, ZoneOffset.UTC);
        List<Candidate> candidates = new ArrayList<>();
        for (DatePart d : dates(text, received.toLocalDate())) {
            candidate(text, d, received).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int best = candidates.stream().mapToInt(Candidate::score).max().orElse(0);
        List<Candidate> top = candidates.stream().filter(c -> c.score() == best).toList();
        Set<String> distinct = new HashSet<>();
        top.forEach(c -> distinct.add(c.start() + "/" + c.zoneKnown()));
        if (distinct.size() > 1) {
            return Optional.empty();
        }
        Candidate c = top.get(0);
        return Optional.of(new Found(c.start(), c.zoneKnown(), c.text()));
    }

    private static List<DatePart> dates(String text, LocalDate received) {
        List<DatePart> out = new ArrayList<>();
        Matcher m = ISO.matcher(text);
        while (m.find() && out.size() < MAX_CANDIDATES) {
            add(out, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.start(), m.end());
        }
        m = NUMERIC.matcher(text);
        while (m.find() && out.size() < MAX_CANDIDATES) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(3));
            boolean slash = "/".equals(m.group(2));
            // 11/12/2026 is 11 December or 12 November: only a number above 12 tells which, and a dotted date is day first.
            if (slash && a <= 12 && b <= 12 && a != b) {
                continue;
            }
            boolean monthFirst = slash && a <= 12 && b > 12;
            add(out, Integer.parseInt(m.group(4)), monthFirst ? a : b, monthFirst ? b : a, m.start(), m.end());
        }
        m = DAY_MONTH.matcher(text);
        while (m.find() && out.size() < MAX_CANDIDATES) {
            addNamed(out, received, m.group(1), m.group(2), m.group(3), m.start(), m.end());
        }
        m = MONTH_DAY.matcher(text);
        while (m.find() && out.size() < MAX_CANDIDATES) {
            addNamed(out, received, m.group(2), m.group(1), m.group(3), m.start(), m.end());
        }
        List<DatePart> explicit = List.copyOf(out);
        m = RELATIVE.matcher(text);
        while (m.find() && out.size() < MAX_CANDIDATES) {
            String word = m.group(1).toLowerCase(Locale.ROOT);
            boolean today = word.equals("today") || word.equals("сьогодні") || word.equals("сегодня");
            int days = today ? 0 : word.equals("tomorrow") || word.equals("завтра") ? 1 : 2;
            out.add(new DatePart(received.plusDays(days), m.start(), m.end()));
        }
        m = WEEKDAY.matcher(text);
        while (m.find() && out.size() < MAX_CANDIDATES) {
            DayOfWeek dow = WEEKDAYS.get(m.group(1).toLowerCase(Locale.ROOT));
            if (dow == null || nextToDate(explicit, m.start(), m.end())) {
                continue;
            }
            // The next such day strictly after the day the mail came: "Tuesday" written on a Tuesday is next week.
            int ahead = (dow.getValue() - received.getDayOfWeek().getValue() + 6) % 7 + 1;
            out.add(new DatePart(received.plusDays(ahead), m.start(), m.end()));
        }
        return out;
    }

    /** A weekday written right beside a date ("Tuesday, 12 November") is part of that date, not a second event. */
    private static boolean nextToDate(List<DatePart> explicit, int start, int end) {
        return explicit.stream().anyMatch(d -> d.start() >= end && d.start() - end <= NEAR || d.end() <= start && start - d.end() <= NEAR
                || d.start() < end && d.end() > start);
    }

    private static void add(List<DatePart> out, int year, int month, int day, int start, int end) {
        try {
            out.add(new DatePart(LocalDate.of(year, month, day), start, end));
        } catch (DateTimeException e) {
            // 31 February and the like: not a date.
        }
    }

    private static void addNamed(List<DatePart> out, LocalDate received, String day, String month, String year, int start, int end) {
        Integer monthNumber = MONTHS.get(month.toLowerCase(Locale.ROOT));
        if (monthNumber == null) {
            return;
        }
        int dayNumber = Integer.parseInt(day);
        if (year != null) {
            add(out, Integer.parseInt(year), monthNumber, dayNumber, start, end);
            return;
        }
        try {
            LocalDate d = LocalDate.of(received.getYear(), monthNumber, dayNumber);
            if (d.isBefore(received)) {
                // "10 January" in a December mail is next year; "5 November" in a mail of 20 November is past.
                d = d.plusYears(1);
                if (d.isAfter(received.plusDays(YEARLESS_DAYS))) {
                    return;
                }
            }
            out.add(new DatePart(d, start, end));
        } catch (DateTimeException e) {
            // 31 April and the like: not a date.
        }
    }

    private static Optional<Candidate> candidate(String text, DatePart d, LocalDateTime received) {
        Optional<TimePart> time = timeAfter(text, d.end()).or(() -> timeBefore(text, d.start()));
        if (time.isEmpty() || time.get().time().getHour() < EARLIEST_HOUR) {
            return Optional.empty();
        }
        TimePart t = time.get();
        boolean zoneKnown = t.offsetSeconds() != null;
        LocalDateTime local = LocalDateTime.of(d.date(), t.time());
        LocalDateTime start = zoneKnown ? local.minusSeconds(t.offsetSeconds()) : local;
        // Past (or the very moment of the mail, such as its own "Sent:" line) and more than a year ahead are not an event.
        if (!start.isAfter(received) || start.isAfter(received.plusDays(HORIZON_DAYS))) {
            return Optional.empty();
        }
        int from = Math.min(d.start(), t.start());
        int to = Math.max(d.end(), t.end());
        String matched = text.substring(from, to).replaceAll("\\s+", " ").strip();
        if (matched.length() > MAX_TEXT) {
            matched = matched.substring(0, MAX_TEXT);
        }
        String around = text.substring(Math.max(0, from - CONTEXT), Math.min(text.length(), to + CONTEXT));
        return Optional.of(new Candidate(start, zoneKnown, matched, TOPIC.matcher(around).find() ? 1 : 0));
    }

    private static Matcher region(Pattern p, String text, int from, int to) {
        return p.matcher(text).region(from, to).useTransparentBounds(true);
    }

    private static Optional<TimePart> timeAfter(String text, int from) {
        int end = Math.min(text.length(), from + TIME_WINDOW);
        Matcher paragraph = region(PARAGRAPH, text, from, end);
        if (paragraph.find()) {
            end = paragraph.start();
        }
        Matcher m = region(TIME, text, from, end);
        while (m.find()) {
            Optional<TimePart> t = timePart(text, m);
            if (t.isPresent()) {
                return t;
            }
        }
        return Optional.empty();
    }

    private static Optional<TimePart> timeBefore(String text, int to) {
        int from = Math.max(0, to - TIME_WINDOW);
        Matcher paragraph = region(PARAGRAPH, text, from, to);
        while (paragraph.find()) {
            from = paragraph.end();
        }
        Matcher m = region(TIME, text, from, to);
        Optional<TimePart> last = Optional.empty();
        while (m.find()) {
            Optional<TimePart> t = timePart(text, m);
            if (t.isPresent()) {
                last = t;
            }
        }
        return last;
    }

    private static Optional<TimePart> timePart(String text, Matcher m) {
        int hour;
        int minute = 0;
        String meridiem;
        boolean seconds = false;
        if (m.group(1) != null) {
            hour = Integer.parseInt(m.group(1));
            minute = Integer.parseInt(m.group(2));
            seconds = m.group(3) != null;
            meridiem = m.group(4);
        } else {
            hour = Integer.parseInt(m.group(5));
            meridiem = m.group(6);
        }
        if (meridiem != null) {
            if (hour < 1 || hour > 12) {
                return Optional.empty();
            }
            hour = hour % 12 + ("p".equalsIgnoreCase(meridiem) ? 12 : 0);
        }
        Zone zone = zoneAt(text, m.end(), seconds);
        return Optional.of(new TimePart(LocalTime.of(hour, minute), zone.offsetSeconds(), m.start(), zone.end()));
    }

    /** The UTC offset in seconds that is written right after a time, or null when the text names no zone. */
    private static Zone zoneAt(String text, int at, boolean hadSeconds) {
        Matcher z = region(ZONE, text, at, Math.min(text.length(), at + ZONE_WINDOW));
        if (!z.lookingAt()) {
            return new Zone(null, at);
        }
        Integer offset = offsetOf(z, at, hadSeconds);
        return offset == null ? new Zone(null, at) : new Zone(offset, z.end());
    }

    private static Integer offsetOf(Matcher z, int at, boolean hadSeconds) {
        if (z.group(1) != null) {
            return 0;
        }
        if (z.group(2) != null) {
            return offset(z.group(2), z.group(3), z.group(4));
        }
        if (z.group(5) != null) {
            // "10:00-11:00" is a range, not an offset; an attached minus sign counts only after seconds.
            boolean attached = z.start() == at;
            if (attached && !"+".equals(z.group(5)) && !hadSeconds) {
                return null;
            }
            return offset(z.group(5), z.group(6), z.group(7));
        }
        return ZONES.get(z.group(8).toLowerCase(Locale.ROOT));
    }

    private static Integer offset(String sign, String hours, String minutes) {
        int h = Integer.parseInt(hours);
        int m = minutes == null ? 0 : Integer.parseInt(minutes);
        if (h > 14 || m % 15 != 0 || m > 45) {
            return null;
        }
        int seconds = (int) Duration.ofHours(h).plusMinutes(m).toSeconds();
        return "+".equals(sign) ? seconds : -seconds;
    }
}
