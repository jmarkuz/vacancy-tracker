package com.vacancytracker.application.packaging;

import java.time.Instant;
import java.util.UUID;

/** The documents chosen for one application and its state: prepared, or submitted by the owner. */
public record ApplicationPackage(
        UUID id,
        UUID vacancyId,
        UUID resumeDocumentId,
        UUID coverLetterDocumentId,
        String applyUrl,
        String note,
        String status,
        Instant createdAt,
        Instant submittedAt) {

    public static final String PREPARED = "prepared";
    public static final String SUBMITTED = "submitted";

    /** A short reference for history notes: the first part of the id. */
    public String reference() {
        return id.toString().substring(0, 8);
    }
}
