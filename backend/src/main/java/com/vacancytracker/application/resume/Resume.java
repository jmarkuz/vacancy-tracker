package com.vacancytracker.application.resume;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The user's resume: its text, the keywords the fit score uses, and a revision that grows with every change. */
public record Resume(
        UUID id,
        String name,
        String fileName,
        String contentType,
        String text,
        List<String> keywords,
        int revision,
        Instant uploadedAt,
        Instant updatedAt) {
}
