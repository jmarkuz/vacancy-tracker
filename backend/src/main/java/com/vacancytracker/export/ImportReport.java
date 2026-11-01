package com.vacancytracker.export;

import java.util.List;

/** Result of an import: rows read, rows stored, and one error per rejected row. */
public record ImportReport(int total, int imported, List<RowError> errors) {

    public record RowError(int row, String field, String message) {
    }
}
