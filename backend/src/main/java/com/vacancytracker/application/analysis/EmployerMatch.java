package com.vacancytracker.application.analysis;

import java.util.List;

/**
 * How well one document covers the employer's checklist (T-095). Code computes the score from the checked statuses:
 * round(100 × Σ(weight × status) / Σ(weight)), weights must 3, important 2, nice 1, status covered 1, partial 0.5,
 * missing 0. The model only proposes statuses and quotes; it cannot set the score.
 */
public record EmployerMatch(int score, List<Item> items) {

    public static final String COVERED = "covered";
    public static final String PARTIAL = "partial";
    public static final String MISSING = "missing";

    /** A requirement with its checked status, the document's own words as proof, and why the status was lowered. */
    public record Item(String requirementId, String text, String priority, String status, String quote, String note) {
    }

    static int score(List<Checklist.Requirement> requirements, List<Item> items) {
        double total = 0;
        double got = 0;
        for (int i = 0; i < requirements.size(); i++) {
            int weight = requirements.get(i).weight();
            total += weight;
            got += weight * switch (items.get(i).status()) {
                case COVERED -> 1.0;
                case PARTIAL -> 0.5;
                default -> 0.0;
            };
        }
        return total == 0 ? 0 : (int) Math.round(100 * got / total);
    }
}
