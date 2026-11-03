package com.vacancytracker.application.analysis;

import java.util.ArrayList;
import java.util.List;
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
        // A Markdown document has its sections under # headings; a plain-text one under short title lines that end at
        // a blank line. A one-word line inside a Markdown Skills section is a skill, not a new heading.
        boolean markdown = document.lines().anyMatch(l -> l.strip().startsWith("#"));
        boolean inSkills = false;
        // The whole text, whitespace normalised, with a mark per character: is it in a Skills section or line? A quote
        // that runs over a line break is then judged by the lines it covers, like any other.
        StringBuilder text = new StringBuilder();
        List<Boolean> skills = new ArrayList<>();
        for (String raw : document.split("\n")) {
            String line = raw.strip();
            if (markdown) {
                if (line.startsWith("#")) {
                    inSkills = SKILLS_HEADING.matcher(line).find();
                }
            } else if (line.isEmpty()) {
                inSkills = false;
            } else if (PLAIN_HEADING.matcher(line).matches()) {
                inSkills = SKILLS_HEADING.matcher(line).find();
            }
            String normalised = normalise(raw);
            if (normalised.isEmpty()) {
                continue;
            }
            boolean skillsLine = inSkills || SKILLS_LINE.matcher(line).lookingAt();
            if (!text.isEmpty()) {
                text.append(' ');
                skills.add(skillsLine);
            }
            text.append(normalised);
            for (int i = 0; i < normalised.length(); i++) {
                skills.add(skillsLine);
            }
        }
        boolean found = false;
        for (int at = text.indexOf(q); at >= 0; at = text.indexOf(q, at + 1)) {
            found = true;
            for (int i = at; i < at + q.length(); i++) {
                if (!skills.get(i)) {
                    return Place.EVIDENCE;
                }
            }
        }
        return found ? Place.SKILLS_ONLY : Place.NOT_FOUND;
    }
}
