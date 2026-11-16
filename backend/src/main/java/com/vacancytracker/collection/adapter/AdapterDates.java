package com.vacancytracker.collection.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Reads a source timestamp. A malformed value leaves the posting undated instead of failing the whole run. */
public final class AdapterDates {

    /** Epoch values from this size up are milliseconds (that is year 5138 in seconds, 1973 in milliseconds). */
    private static final long MILLIS_FROM = 100_000_000_000L;

    private AdapterDates() {
    }

    /**
     * A Unix time that may be in seconds or in milliseconds (sources are not consistent, and a count of seconds read as
     * milliseconds gives January 1970). Zero and negative values mean "not given" and give null.
     */
    public static Instant fromEpoch(long value) {
        if (value <= 0) {
            return null;
        }
        return value >= MILLIS_FROM ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
    }

    /** A JSON field that is an epoch number or a date text; missing, null or unreadable gives null. */
    public static Instant fromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isNumber() ? fromEpoch(node.asLong()) : instantOrNull(node.asText(null));
    }

    public static Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("\\d{9,15}")) {
            return fromEpoch(Long.parseLong(text));
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            // Some sources send an offset ("+02:00"), a plain "2026-10-30 10:00:00" (read as UTC), a date or an RFC 1123 date.
            return otherFormats(text);
        }
    }

    private static Instant otherFormats(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // Next format.
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Next format.
        }
        try {
            return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Next format.
        }
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
