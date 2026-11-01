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
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
