package com.vacancytracker.application.documents;

import java.time.Instant;
import java.util.UUID;

/** A saved version of a resume or cover letter for one vacancy. kind: resume or cover_letter; origin: ai or manual. */
public record TailoredDocument(
        UUID id,
        UUID vacancyId,
        String kind,
        int version,
        String content,
        String origin,
        String provider,
        String model,
        Instant createdAt) {

    public static final String RESUME = "resume";
    public static final String COVER_LETTER = "cover_letter";

    /** A file name such as "resume-v2-Acme.docx", safe on every file system. */
    public String fileName(String company, String extension) {
        String safe = company == null ? "" : company.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-|-$)", "");
        String base = (RESUME.equals(kind) ? "resume" : "cover-letter") + "-v" + version + (safe.isEmpty() ? "" : "-" + safe);
        return base + "." + extension;
    }
}
