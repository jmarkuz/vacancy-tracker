package com.vacancytracker.application.analysis;

import java.util.regex.Pattern;

/**
 * Checks a status the model proposed (T-095): the quote must really be in the document (whitespace aside), and a
 * requirement named only in a Skills section or a "Skills:" line counts at most as partial, because covered needs
 * proof in experience or projects.
 */
final class QuoteCheck {

    /** Where a quote stands in the document. */
    enum Place { NOT_FOUND, SKILLS_ONLY, EVIDENCE }

    private static final Pattern SKILLS_HEADING = Pattern.compile(
            "(?iu)\\b(skills?|technical skills|technologies|tech stack|stack|competenc\\w*|навички|технології|навыки|kenntnisse|umiejętności)\\b");
    private static final Pattern SKILLS_LINE = Pattern.compile(
            "(?iu)^[-*•\\s]*(skills?|technical skills|technologies|tech stack|languages|frameworks|tools|infrastructure( and data)?|навички|технології)\\s*:");
    private static final Pattern PLAIN_HEADING = Pattern.compile("^[\\p{L} &/]{2,40}:?$");

    private QuoteCheck() {
    }

    static String normalise(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    static Place place(String document, String quote) {
        String q = normalise(quote);
        if (q.length() < 2 || document == null) {
            return Place.NOT_FOUND;
        }
        boolean inMarkdownSkills = false;
        boolean inPlainSkills = false;
        boolean skillsHit = false;
        boolean evidenceHit = false;
        for (String raw : document.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("#")) {
                inMarkdownSkills = SKILLS_HEADING.matcher(line).find();
                inPlainSkills = false;
            } else if (line.isEmpty()) {
                inPlainSkills = false;
            } else if (PLAIN_HEADING.matcher(line).matches()) {
                inPlainSkills = SKILLS_HEADING.matcher(line).find();
                if (!inPlainSkills) {
                    inMarkdownSkills = false;
                }
            }
            if (normalise(raw).contains(q)) {
                if (inMarkdownSkills || inPlainSkills || SKILLS_LINE.matcher(line).lookingAt()) {
                    skillsHit = true;
                } else {
                    evidenceHit = true;
                }
            }
        }
        if (evidenceHit) {
            return Place.EVIDENCE;
        }
        if (skillsHit) {
            return Place.SKILLS_ONLY;
        }
        // A quote that runs over a line break.
        return normalise(document).contains(q) ? Place.EVIDENCE : Place.NOT_FOUND;
    }
}
