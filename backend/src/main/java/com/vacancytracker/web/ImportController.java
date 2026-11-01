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

    private final VacancyImporter importer;

    public ImportController(VacancyImporter importer) {
        this.importer = importer;
    }

    /** JSON backup (schema version 1). Whole-document errors give 422; row errors come back in the report. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ImportReport importJson(@RequestBody String body) {
        return importer.importJson(body);
    }

    @PostMapping(consumes = "text/csv")
    public ImportReport importCsv(@RequestBody String body) {
        return importer.importCsv(body);
    }
}
