package com.vacancytracker.application.fit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stored fit of one vacancy to the resume revision it was computed for. */
public record ResumeFit(
        UUID vacancyId,
        UUID resumeId,
        int resumeRevision,
        int score,
        List<String> matched,
        List<String> missing,
        List<ResumeFitScorer.Reason> reasons,
        Instant computedAt) {
}
