package com.vacancytracker.web;

import com.vacancytracker.export.ImportReport;
import com.vacancytracker.export.VacancyImporter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/import/vacancies")
public class ImportController {

    /** A personal list is far below this; it only stops a mistaken or hostile body from filling the memory. */
    static final int MAX_BODY_BYTES = 20 * 1024 * 1024;

    private final VacancyImporter importer;

    public ImportController(VacancyImporter importer) {
        this.importer = importer;
    }

    /** JSON backup (schema version 1). Whole-document errors give 422; row errors come back in the report. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ImportReport importJson(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        return importer.importJson(readBounded(request));
    }

    @PostMapping(consumes = "text/csv")
    public ImportReport importCsv(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        return importer.importCsv(readBounded(request));
    }

    /** Reads the body up to the limit and no further, so an oversized upload is refused without being held in memory. */
    private static String readBounded(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "The file is larger than " + MAX_BODY_BYTES / (1024 * 1024) + " MB");
        }
        String charset = request.getCharacterEncoding();
        return new String(bytes, charset == null ? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(charset));
    }
}
