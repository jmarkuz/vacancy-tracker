package com.vacancytracker.application.preset;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An AI preset (T-093, ADR-047): what the owner is looking for, how much each parameter of the goal matters, how to
 * adapt each document, and the base documents the adaptation starts from. baseResumeId points at a row of the resume
 * table (shared with the Resume screen); null means the preset still has the default template.
 */
public record Preset(
        UUID id,
        String name,
        boolean isDefault,
        String mainGoal,
        List<Weight> weights,
        String resumePrompt,
        String coverLetterPrompt,
        UUID baseResumeId,
        String baseCoverLetter,
        String baseCoverLetterFileName,
        Instant createdAt,
        Instant updatedAt) {

    /** One parameter of the goal and its weight; weights are relative, their sum need not be 100. */
    public record Weight(String name, int weight) {
    }

    /** Each parameter's actual share of the total (0..1), in the order of {@link #weights()}. */
    public List<Double> shares() {
        int total = weights.stream().mapToInt(Weight::weight).sum();
        return weights.stream().map(w -> total == 0 ? 0.0 : (double) w.weight() / total).toList();
    }
}
