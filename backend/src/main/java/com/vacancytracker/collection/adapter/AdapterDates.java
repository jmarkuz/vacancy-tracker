package com.vacancytracker.collection.adapter;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Reads a source timestamp. A malformed value leaves the posting undated instead of failing the whole run. */
public final class AdapterDates {

    private AdapterDates() {
    }

    public static Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            // Some sources send an offset ("+02:00") or a plain "2026-10-30 10:00:00", read as UTC.
            try {
                return java.time.OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                try {
                    return java.time.LocalDateTime.parse(text.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC);
                } catch (DateTimeParseException alsoIgnored) {
                    return null;
                }
            }
        }
    }
}
