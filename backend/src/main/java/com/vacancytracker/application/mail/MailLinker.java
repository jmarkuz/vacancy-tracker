package com.vacancytracker.application.mail;

import com.vacancytracker.collection.mail.MailMessage;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Finds the application a job email is about (T-087, ADR-043). Each candidate vacancy is scored on the evidence the
 * mail carries: the company's name in the subject, the sender's name or the text; the sender's domain matching the
 * company or its posting; the vacancy title in the mail; and whether the owner applied (answers follow applications).
 * The best candidate with enough evidence wins, with the evidence as the reason; otherwise the mail stays unlinked.
 */
public final class MailLinker {

    static final int THRESHOLD = 3;
    private static final Set<String> LEGAL = Set.of("inc", "llc", "ltd", "limited", "gmbh", "ag", "sa", "sro", "s.r.o", "sp", "z", "o.o",
            "oo", "bv", "plc", "corp", "corporation", "co", "company", "group", "tov", "тов", "llp", "ou", "oü", "ab", "as");
    private static final Set<String> PUBLIC_MAIL = Set.of("gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "yahoo.com",
            "icloud.com", "proton.me", "protonmail.com", "ukr.net", "i.ua", "meta.ua");

    private static final Set<String> JOB_BOARDS = Set.of("linkedin.com", "indeed.com", "glassdoor.com", "djinni.co", "dou.ua", "work.ua",
            "robota.ua", "jooble.org", "ziprecruiter.com", "greenhouse.io", "lever.co", "workable.com", "smartrecruiters.com",
            "ashbyhq.com", "recruitee.com", "teamtailor.com", "justjoin.it", "nofluffjobs.com", "jobs.cz", "startup.jobs", "wellfound.com");

    /** A vacancy the mail may be about. applied is true from Applied on (or with a submitted package). */
    public record Candidate(UUID vacancyId, String title, String company, String url, boolean applied) {
    }

    public record Link(UUID vacancyId, int score, String reason) {
    }

    private MailLinker() {
    }

    public static Optional<Link> link(MailMessage mail, List<Candidate> candidates) {
        String subject = MailClassifier.lower(mail.subject());
        String sender = MailClassifier.lower(mail.senderName());
        String body = MailClassifier.lower(MailClassifier.body(mail));
        String fromDomain = domain(mail.from());
        Link best = null;
        for (Candidate c : candidates) {
            int score = 0;
            List<String> why = new ArrayList<>();
            String company = companyKey(c.company());
            if (!company.isEmpty()) {
                if (mentions(subject, company) || mentions(sender, company)) {
                    score += 4;
                    why.add("the company name is in the " + (mentions(subject, company) ? "subject" : "sender's name"));
                } else if (mentions(body, company)) {
                    score += 3;
                    why.add("the company name is in the text");
                }
                String compact = company.replace(" ", "");
                // A job board hosts many companies' postings, so its domain says nothing about which vacancy a mail is about.
                String postingHost = postingHost(c.url());
                boolean sharedHost = JOB_BOARDS.contains(fromDomain) || JOB_BOARDS.contains(postingHost);
                if (!fromDomain.isEmpty() && !PUBLIC_MAIL.contains(fromDomain) && compact.length() >= 3
                        && (fromDomain.replace("-", "").contains(compact) || (!sharedHost && postingHost.equals(fromDomain)))) {
                    score += 3;
                    why.add("the sender's domain matches the company or its posting");
                }
            }
            String title = MailClassifier.lower(c.title());
            if (title.length() >= 4 && (subject.contains(title) || body.contains(title))) {
                score += 2;
                why.add("the vacancy title is in the mail");
            }
            if (c.applied() && score > 0) {
                score += 1;
                why.add("you applied");
            }
            if (score >= THRESHOLD && (best == null || score > best.score())) {
                best = new Link(c.vacancyId(), score, capitalise(String.join("; ", why)) + ".");
            }
        }
        return Optional.ofNullable(best);
    }

    /** True for a job board's own address (noreply@linkedin.com): one sender writes about many vacancies, so it identifies none. */
    public static boolean sharedSender(String address) {
        return JOB_BOARDS.contains(domain(address));
    }

    /** "Acme Pay, Inc." becomes "acme pay": lower case, punctuation and legal forms removed. */
    static String companyKey(String company) {
        if (company == null) {
            return "";
        }
        String plain = company.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        return String.join(" ", Arrays.stream(plain.split(" ")).filter(w -> !w.isEmpty() && !LEGAL.contains(w)).toList());
    }

    /** Compiled company patterns, kept between mails: a sync matches every mail against every vacancy's company. */
    static final int PATTERN_CACHE_SIZE = 2048;
    private static final Map<String, Pattern> PATTERNS = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
            return size() > PATTERN_CACHE_SIZE;
        }
    });

    static int cachedPatterns() {
        return PATTERNS.size();
    }

    private static Pattern phrasePattern(String phrase) {
        return PATTERNS.computeIfAbsent(phrase, p -> Pattern.compile("(?<![\\p{L}\\p{N}])"
                + Pattern.quote(p).replace(" ", "\\E[\\s\\p{Punct}]+\\Q") + "(?![\\p{L}\\p{N}])",
                Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE));
    }

    private static boolean mentions(String text, String phrase) {
        return !text.isEmpty() && phrasePattern(phrase).matcher(text).find();
    }

    private static String domain(String address) {
        if (address == null || !address.contains("@")) {
            return "";
        }
        String host = address.substring(address.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT).trim();
        String[] parts = host.split("\\.");
        return parts.length <= 2 ? host : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static String postingHost(String url) {
        try {
            String host = url == null ? null : URI.create(url).getHost();
            return host == null ? "-" : domain("x@" + host);
        } catch (IllegalArgumentException e) {
            return "-";
        }
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
