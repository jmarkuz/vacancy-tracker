package com.vacancytracker.application.documents;

import com.vacancytracker.application.preset.Preset;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one document was made from (T-094, ADR-048), copied from the preset when it was generated or used as it is:
 * the guardrail, the main goal, the weights, the document prompt and the base draft. baseResume is the base resume
 * given as the source of facts with a cover letter; baseResumeId is the stored resume a resume came from, so its
 * uploaded PDF can be used as it is.
 */
public record GenerationSnapshot(
        UUID id,
        UUID vacancyId,
        String kind,
        UUID presetId,
        String presetName,
        String guardrail,
        String mainGoal,
        List<Preset.Weight> weights,
        String prompt,
        String baseDraft,
        String baseResume,
        UUID baseResumeId,
        Instant createdAt) {
}
