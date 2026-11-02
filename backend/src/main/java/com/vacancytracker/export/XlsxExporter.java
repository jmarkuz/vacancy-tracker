package com.vacancytracker.export;

import com.vacancytracker.vacancy.VacancyStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;

/** XLSX export with a streaming workbook, so memory use stays flat for large sets (ADR-016). */
@Component
public class XlsxExporter {

    static final int IN_MEMORY_ROWS = 200;

    private final VacancyExporter exporter;

    public XlsxExporter(VacancyExporter exporter) {
        this.exporter = exporter;
    }

    public void write(OutputStream out, VacancyStatus status, List<ExportColumn> columns) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(IN_MEMORY_ROWS)) {
            Sheet sheet = workbook.createSheet("Vacancies");
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i).id());
            }
            int[] next = {1};
            exporter.forEach(status, v -> {
                Row row = sheet.createRow(next[0]++);
                for (int i = 0; i < columns.size(); i++) {
                    setCell(row.createCell(i), columns.get(i).value(v));
                }
            });
            workbook.write(out);
        }
    }

    private static final int MAX_CELL_CHARS = 32_767;

    private static void setCell(org.apache.poi.ss.usermodel.Cell cell, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Integer number) {
            cell.setCellValue(number);
        } else {
            String text = value.toString();
            // Excel refuses a cell over 32,767 characters, and one long description must not fail the whole export.
            cell.setCellValue(text.length() > MAX_CELL_CHARS ? text.substring(0, MAX_CELL_CHARS - 1) + "…" : text);
        }
    }
}
