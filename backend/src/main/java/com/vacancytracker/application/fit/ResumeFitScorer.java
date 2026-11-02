package com.vacancytracker.application.fit;

import com.vacancytracker.application.resume.Experience;
import com.vacancytracker.application.resume.Terms;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * How well a vacancy fits the resume (T-081, ADR-040), deterministic and explained.
 *
 * <p>The vacancy "asks" for its skill-like terms ({@link Terms#skillLikeTerms}) plus every resume keyword it mentions.
 * Coverage is the share of those the resume has. Score = 85 x coverage, +15 when a resume keyword is in the title,
 * -15 when the vacancy asks for more years than the resume shows, clamped to 0..100. A vacancy with almost no text
 * is scored on its title only and says so.
 */
public final class ResumeFitScorer {

    static final int COVERAGE_POINTS = 85;
    static final int TITLE_BONUS = 15;
    static final int YEARS_PENALTY = 15;
    static final int THIN_TEXT_CHARS = 200;
    static final int LISTED = 8;

    private ResumeFitScorer() {
    }

    /** resumeYears is what the resume shows (empty when unknown). */
    public static Result score(List<String> keywords, OptionalInt resumeYears, String resumeText, String title, String description) {
        String body = description == null ? "" : description;
        String all = (title == null ? "" : title) + "\n" + body;
        List<String> matched = new ArrayList<>();
        boolean inTitle = false;
        for (String k : keywords) {
            if (Terms.mentions(all, k)) {
                matched.add(k);
                inTitle |= Terms.mentions(title, k);
            }
        }
        Map<String, String> asked = new LinkedHashMap<>();
        matched.forEach(k -> asked.putIfAbsent(Terms.key(k), k));
        List<String> missing = new ArrayList<>();
        for (String term : Terms.skillLikeTerms(all)) {
            String key = Terms.key(term);
            if (asked.containsKey(key)) {
                continue;
            }
            asked.put(key, term);
            boolean resumeHasIt = keywords.stream().anyMatch(k -> Terms.key(k).equals(key)) || Terms.mentions(resumeText, term);
            if (resumeHasIt) {
                matched.add(term);
            } else {
                missing.add(term);
            }
        }

        List<Reason> reasons = new ArrayList<>();
        boolean thin = body.strip().length() < THIN_TEXT_CHARS;
        double coverage = asked.isEmpty() ? 0 : (double) matched.size() / asked.size();
        int score;
        if (asked.isEmpty()) {
            score = 0;
            reasons.add(new Reason("thin", "The vacancy names no skills the score can compare."));
        } else {
            score = (int) Math.round(COVERAGE_POINTS * coverage);
            reasons.add(new Reason("matched", matched.isEmpty() ? "None of the skills the vacancy names are in your resume."
                    : "Matches " + matched.size() + " of " + asked.size() + " skills the vacancy names: " + list(matched) + "."));
        }
        if (!missing.isEmpty()) {
            reasons.add(new Reason("missing", "Not in your resume: " + list(missing) + "."));
        }
        if (inTitle) {
            score += TITLE_BONUS;
            reasons.add(new Reason("title", "The title mentions a skill from your resume."));
        }
        OptionalInt required = Experience.required(body);
        if (required.isPresent() && resumeYears.isPresent()) {
            boolean fewer = resumeYears.getAsInt() < required.getAsInt();
            if (fewer) {
                score -= YEARS_PENALTY;
            }
            reasons.add(new Reason("years", "Asks for " + required.getAsInt() + "+ years; your resume shows about "
                    + resumeYears.getAsInt() + (fewer ? ", fewer than asked." : ".")));
        }
        if (thin && !asked.isEmpty()) {
            reasons.add(new Reason("thin", "The vacancy has little text, so the score rests mostly on the title."));
        }
        int clamped = Math.max(0, Math.min(100, score));
        return new Result(clamped, List.copyOf(matched), List.copyOf(missing), List.copyOf(reasons));
    }

    private static String list(List<String> terms) {
        String shown = String.join(", ", terms.subList(0, Math.min(LISTED, terms.size())));
        return terms.size() > LISTED ? shown + " and " + (terms.size() - LISTED) + " more" : shown;
    }

    /** kind: matched, missing, title, years or thin. */
    public record Reason(String kind, String text) {
    }

    public record Result(int score, List<String> matched, List<String> missing, List<Reason> reasons) {
    }
}
