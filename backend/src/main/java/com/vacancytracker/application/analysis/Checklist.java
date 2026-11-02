package com.vacancytracker.application.analysis;

import java.util.List;

/**
 * What the employer asks for (T-095): the core problem the hire should solve and the requirements by priority. Built
 * once per vacancy text by AI; the ids (r1, r2, …) are given by the server.
 */
public record Checklist(String coreProblem, List<Requirement> requirements) {

    public static final String MUST = "must";
    public static final String IMPORTANT = "important";
    public static final String NICE = "nice";

    /** One requirement; terms are the words a document would use for it. */
    public record Requirement(String id, String text, String priority, List<String> terms) {

        /** must-have 3, important 2, nice-to-have 1. */
        public int weight() {
            return switch (priority) {
                case MUST -> 3;
                case IMPORTANT -> 2;
                default -> 1;
            };
        }
    }
}
