package com.vacancytracker.export;

import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancyStatus;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Streams vacancies in batches so large exports do not load everything into memory. */
@Service
public class VacancyExporter {

    static final int BATCH = 500;

    private final VacancyService vacancies;

    public VacancyExporter(VacancyService vacancies) {
        this.vacancies = vacancies;
    }

    public void forEach(VacancyStatus status, Consumer<Vacancy> consumer) {
        int page = 0;
        while (true) {
            List<Vacancy> batch = vacancies.list(status, page, BATCH);
            batch.forEach(consumer);
            if (batch.size() < BATCH) {
                return;
            }
            page++;
        }
    }

    /** CSV: UTF-8 with BOM, CRLF line endings, RFC 4180 quoting (ADR-016). */
    public void writeCsv(Writer out, VacancyStatus status, List<ExportColumn> columns) throws IOException {
        out.write('﻿');
        writeLine(out, columns.stream().map(ExportColumn::id).toList());
        try {
            forEach(status, v -> {
                try {
                    writeLine(out, columns.stream().map(c -> text(c.value(v))).toList());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        out.flush();
    }

    private static void writeLine(Writer out, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(cells.get(i)));
        }
        out.write("\r\n");
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        // A spreadsheet runs a cell that starts with these as a formula; vacancy text comes from third parties.
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
