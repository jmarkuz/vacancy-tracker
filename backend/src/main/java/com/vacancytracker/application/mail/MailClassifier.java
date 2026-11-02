package com.vacancytracker.application.mail;

import com.vacancytracker.collection.mail.MailMessage;
import com.vacancytracker.collection.mail.MailParser;
import com.vacancytracker.collection.normalize.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sorts mail into job categories by its own words (T-086, ADR-043): English, Ukrainian, Russian, Polish and German
 * phrasings of rejections, offers, test tasks, interview invitations, confirmations and LinkedIn notices. Rule-based
 * on purpose: mail is never sent to an AI. A refusal is checked before an invitation or a confirmation, because
 * "thank you for applying ... unfortunately" is a rejection.
 */
public final class MailClassifier {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    /** At most this much of a mail's text is classified, linked and shown. */
    static final int MAX_TEXT = 50_000;
    private static final Map<MailCategory, Pattern> RULES = new java.util.LinkedHashMap<>();

    static {
        rule(MailCategory.REJECTION, "unfortunately", "regret to inform", "not (?:be )?(?:moving|move) forward",
                "decided to (?:move forward|proceed|go forward|continue) with (?:other|another)", "not been selected",
                "will not be (?:moving|proceeding|progressing)", "position has (?:now )?been filled", "decided not to proceed",
                "not (?:a|the right) (?:match|fit)", "на жаль", "к сожалению", "niestety", "leider", "відмов", "отказ");
        rule(MailCategory.OFFER, "offer letter", "pleased to offer", "happy to offer", "job offer", "extend (?:an|the) offer",
                "пропозиці\\w* (?:про|щодо) робот", "оффер", "job-?offer", "ofert\\w* prac");
        rule(MailCategory.TEST_TASK, "test task", "take[- ]home", "coding (?:challenge|assignment|test|exercise)",
                "home assignment", "technical (?:assignment|task|test)", "codility", "hackerrank", "codesignal", "testgorilla",
                "тестов\\w* завдан", "тестов\\w* задани", "zadani\\w* rekrutacyjn", "zadani\\w* domow", "probeaufgabe");
        rule(MailCategory.INTERVIEW, "interview", "schedule (?:a|an|the) (?:call|meeting|chat|conversation)", "book a (?:time|slot)",
                "calendly\\.com", "invite you to (?:a|an|the) ", "phone screen", "співбесід", "собеседован", "інтерв.ю", "интервью",
                "rozmow\\w* kwalifikacyjn", "vorstellungsgespr");
        rule(MailCategory.APPLICATION_VIEWED, "your application was viewed", "viewed your application", "переглянув ваш\\w* відгук");
        rule(MailCategory.APPLICATION_RECEIVED, "thank you for (?:applying|your application|your interest)",
                "thanks for (?:applying|your application)", "(?:we(?: have|'ve)? )?received your application",
                "application (?:has been )?received", "your application was sent", "application (?:was |has been )?submitted",
                "дякуємо за (?:ваш\\w* )?(?:відгук|резюме|заявк|інтерес)", "спасибо за (?:ваш\\w* )?(?:отклик|резюме)",
                "dziękujemy za (?:aplikacj|zgłoszeni)", "vielen dank für ihre bewerbung");
        rule(MailCategory.RECRUITER_MESSAGE, "sent you a (?:new )?message", "inmail", "new message from",
                "(?:recruiter|talent acquisition|hiring manager|рекрутер)\\b.{0,200}(?:position|role|opportunit|вакансі)",
                "came across your profile", "your profile (?:caught|matches)");
        rule(MailCategory.JOB_ALERT, "job alert", "jobs? (?:recommended )?for you", "new jobs? (?:match|matching|that match)",
                "recommended jobs", "jobs you may be interested", "нові вакансії", "новые вакансии", "nowe oferty pracy");
    }

    private static final Pattern JOB_WORDS = Pattern.compile("\\b(?:vacanc\\w*|position|role|job|application|applicant|candidate|"
            + "resume|cv|hiring|recruit\\w*|вакансі\\w*|резюме|кандидат\\w*|роботодав\\w*|работодател\\w*|stanowisk\\w*|rekrutac\\w*|bewerbung\\w*)\\b", FLAGS);

    private MailClassifier() {
    }

    private static void rule(MailCategory category, String... phrases) {
        RULES.put(category, Pattern.compile("(?:" + String.join("|", phrases) + ")", FLAGS));
    }

    public static MailCategory classify(MailMessage mail) {
        String subject = mail.subject() == null ? "" : mail.subject().length() > 1000 ? mail.subject().substring(0, 1000) : mail.subject();
        String body = body(mail);
        String all = subject + "\n" + body;
        // An alert says so in its subject; checked first, because alerts often carry words such as "interview".
        if (RULES.get(MailCategory.JOB_ALERT).matcher(subject).find()) {
            return MailCategory.JOB_ALERT;
        }
        // LinkedIn's own notices are told apart by their wording, not by the sender alone (alerts come from it too).
        for (Map.Entry<MailCategory, Pattern> r : RULES.entrySet()) {
            if (r.getKey() == MailCategory.JOB_ALERT && !looksLikeAlert(mail)) {
                continue;
            }
            if (r.getValue().matcher(all).find()) {
                return r.getKey();
            }
        }
        long jobWords = JOB_WORDS.matcher(all).results().count();
        return jobWords >= 2 || jobWords >= 1 && MailParser.fromDomain(mail.from(), "linkedin.com") ? MailCategory.OTHER_JOB : MailCategory.NOT_JOB;
    }

    /** Alert words in the body count only when the sender is a job site (the sources the tool knows). */
    private static boolean looksLikeAlert(MailMessage mail) {
        return List.of("linkedin.com", "indeed.com", "djinni.co", "dou.ua", "jobs.cz", "startupjobs.cz", "workingnomads.com")
                .stream().anyMatch(d -> MailParser.fromDomain(mail.from(), d));
    }

    /**
     * Plain text of the mail: the text part, or the HTML reduced to text, cut at {@link #MAX_TEXT} characters. Mail comes
     * from anyone, so no pattern ever runs over more than that.
     */
    static String body(MailMessage mail) {
        String text;
        if (mail.text() != null && !mail.text().isBlank()) {
            text = mail.text();
        } else {
            String html = mail.html() == null ? "" : mail.html();
            text = Normalizer.plainText(html.length() > MAX_TEXT * 4 ? html.substring(0, MAX_TEXT * 4) : html);
        }
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    /** The first characters of the body, one line per paragraph, for the list. */
    static String snippet(MailMessage mail, int max) {
        String raw = body(mail);
        String text = (raw.length() > max * 4 ? raw.substring(0, max * 4) : raw).replaceAll("[ \\t\\u00A0]+", " ").replaceAll("\\s*\\R\\s*", "\n").strip();
        return text.length() > max ? text.substring(0, max).strip() + "…" : text;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
