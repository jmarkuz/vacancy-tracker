package com.vacancytracker.application.documents;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved version of a resume or cover letter for one vacancy. kind: resume or cover_letter; origin: ai (adapted by
 * AI, then checked by the owner), manual (written by hand) or base (the preset's base draft used as it is).
 * snapshotId points at what it was made from (ADR-048); null for versions made before presets and for manual ones.
 */
public record TailoredDocument(
        UUID id,
        UUID vacancyId,
        String kind,
        int version,
        String content,
        String origin,
        String provider,
        String model,
        Instant createdAt,
        UUID snapshotId,
        /** Pages of the stored PDF (a resume saved since T-097 has one, of 1 or 2 pages); null otherwise. */
        Integer pdfPages,
        /** Facts of the text that its base draft does not have (T-098), and whether the owner confirmed them as true. */
        java.util.List<FactCheck.Finding> factFindings,
        boolean factsConfirmed) {

    public static final String RESUME = "resume";
    public static final String COVER_LETTER = "cover_letter";
    public static final String AI = "ai";
    public static final String MANUAL = "manual";
    public static final String BASE = "base";

    /** A file name such as "resume-v2-Acme.docx", safe on every file system. */
    public String fileName(String company, String extension) {
        String safe = company == null ? "" : company.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-|-$)", "");
        String base = (RESUME.equals(kind) ? "resume" : "cover-letter") + "-v" + version + (safe.isEmpty() ? "" : "-" + safe);
        return base + "." + extension;
    }
}
