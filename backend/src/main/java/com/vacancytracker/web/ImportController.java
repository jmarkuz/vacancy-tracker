package com.vacancytracker.web;

import com.vacancytracker.export.ImportReport;
import com.vacancytracker.export.VacancyImporter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/import/vacancies")
public class ImportController {

    /** A personal list is far below this; it only stops a mistaken or hostile body from filling the memory. */
    static final int MAX_BODY_CHARS = 20 * 1024 * 1024;

    private final VacancyImporter importer;

    public ImportController(VacancyImporter importer) {
        this.importer = importer;
    }

    /** JSON backup (schema version 1). Whole-document errors give 422; row errors come back in the report. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ImportReport importJson(@RequestBody String body) {
        requireSize(body);
        return importer.importJson(body);
    }

    @PostMapping(consumes = "text/csv")
    public ImportReport importCsv(@RequestBody String body) {
        requireSize(body);
        return importer.importCsv(body);
    }

    private static void requireSize(String body) {
        if (body.length() > MAX_BODY_CHARS) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "The file is larger than " + MAX_BODY_CHARS / (1024 * 1024) + " MB");
        }
    }
}
