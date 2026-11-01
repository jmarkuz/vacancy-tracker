package com.vacancytracker.web;

import com.vacancytracker.export.ExportColumn;
import com.vacancytracker.export.VacancyExporter;
import com.vacancytracker.export.XlsxExporter;
import com.vacancytracker.vacancy.VacancyStatus;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final VacancyExporter exporter;
    private final XlsxExporter xlsxExporter;

    public ExportController(VacancyExporter exporter, XlsxExporter xlsxExporter) {
        this.exporter = exporter;
        this.xlsxExporter = xlsxExporter;
    }

    @GetMapping(value = "/vacancies.xlsx")
    public ResponseEntity<StreamingResponseBody> xlsx(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String columns) {
        VacancyStatus filter = status == null || status.isBlank() ? null : VacancyStatus.fromLabel(status);
        List<ExportColumn> cols = ExportColumn.parse(columns);
        StreamingResponseBody body = out -> xlsxExporter.write(out, filter, cols);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("vacancies-" + LocalDate.now() + ".xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @GetMapping(value = "/vacancies.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> csv(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String columns) {
        VacancyStatus filter = status == null || status.isBlank() ? null : VacancyStatus.fromLabel(status);
        List<ExportColumn> cols = ExportColumn.parse(columns);
        StreamingResponseBody body = out -> exporter.writeCsv(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), filter, cols);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("vacancies-" + LocalDate.now() + ".csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
