package com.vacancytracker.export;

import java.util.List;
import java.util.Map;

/**
 * Result of an import: rows read, rows stored, and one error per rejected row. restored counts the records of the
 * other backup sections (such as presets) by section name; empty for CSV.
 */
public record ImportReport(int total, int imported, List<RowError> errors, Map<String, Integer> restored) {

    public record RowError(int row, String field, String message) {
    }
}
