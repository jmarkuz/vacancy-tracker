package com.vacancytracker.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.application.documents.FactCheck;
import com.vacancytracker.application.documents.FactsNotConfirmedException;
import com.vacancytracker.application.documents.ResumeTooLongException;
import com.vacancytracker.application.resume.UnsupportedResumeException;
import com.vacancytracker.collection.NoJobPostingException;
import com.vacancytracker.collection.UrlFetchException;
import com.vacancytracker.export.VacancyImporter;
import com.vacancytracker.matching.CriterionException;
import com.vacancytracker.matching.SavedViewNotFoundException;
import com.vacancytracker.vacancy.InvalidStatusTransitionException;
import com.vacancytracker.vacancy.VacancyNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Every mapping of {@link ApiExceptionHandler}, without a database: a throwing controller behind standalone MockMvc. */
class ApiExceptionHandlerTest {

    private VacancyImporter importer;
    private MockMvc mvc;

    @RestController
    static class Thrower {
        @GetMapping("/t/{kind}")
        String fail(@PathVariable String kind) {
            throw switch (kind) {
                case "vacancy" -> new VacancyNotFoundException(UUID.randomUUID());
                case "view" -> new SavedViewNotFoundException(UUID.randomUUID());
                case "transition" -> new InvalidStatusTransitionException(
                        com.vacancytracker.vacancy.VacancyStatus.NEW, com.vacancytracker.vacancy.VacancyStatus.OFFER);
                case "dup-url" -> new DuplicateKeyException("duplicate key value violates unique constraint \"vacancy_canonical_url_uk\"");
                case "dup-preset" -> new DuplicateKeyException("duplicate key value violates unique constraint \"ai_preset_name_uk\"");
                case "dup-view" -> new DuplicateKeyException("duplicate key value violates unique constraint \"saved_view_name_key\"");
                case "dup-other" -> new DuplicateKeyException("duplicate key value violates unique constraint \"something_else\"");
                case "integrity" -> new DataIntegrityViolationException("value too long");
                case "no-posting" -> new NoJobPostingException("https://example.com/x");
                case "fetch" -> new UrlFetchException("cannot read");
                case "facts" -> new FactsNotConfirmedException(List.of(new FactCheck.Finding("number", "42", "line")));
                case "long" -> new ResumeTooLongException(3, "The resume would have 3 pages.");
                case "unsupported" -> new UnsupportedResumeException("Save it as PDF.");
                case "criterion" -> new CriterionException("Unknown status: x");
                case "iae" -> new IllegalArgumentException("bad");
                case "rse" -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Too big");
                default -> new IllegalStateException("secret internals");
            };
        }

        @GetMapping("/uuid/{id}")
        String uuid(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/param")
        String param(@RequestParam String required) {
            return required;
        }
    }

    @BeforeEach
    void setUp() {
        importer = mock(VacancyImporter.class);
        mvc = MockMvcBuilders.standaloneSetup(new Thrower(), new ImportController(importer))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private void expect(String kind, int status, String title, String detailPart) throws Exception {
        mvc.perform(get("/t/" + kind))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(detailPart)));
    }

    @Test
    void mapsNotFoundAndConflicts() throws Exception {
        expect("vacancy", 404, "Not found", "");
        expect("view", 404, "Not found", "");
        expect("transition", 409, "Invalid status transition", "");
    }

    @Test
    void duplicateKeyNamesTheViolatedConstraint() throws Exception {
        expect("dup-url", 409, "Conflicting data", "canonical URL");
        expect("dup-preset", 409, "Conflicting data", "preset with this name");
        expect("dup-view", 409, "Conflicting data", "saved view");
        expect("dup-other", 409, "Conflicting data", "already exists");
        expect("integrity", 422, "Value not allowed", "too long");
    }

    @Test
    void mapsSourceErrors() throws Exception {
        expect("no-posting", 422, "No job data on page", "");
        expect("fetch", 502, "Source unavailable", "cannot read");
    }

    @Test
    void domainExceptionsCarryTheirOwnFields() throws Exception {
        expect("facts", 422, "Facts not confirmed", "42");
        mvc.perform(get("/t/facts")).andExpect(jsonPath("$.findings[0].value").value("42"));
        expect("long", 422, "Resume is too long", "3 pages");
        mvc.perform(get("/t/long")).andExpect(jsonPath("$.pages").value(3));
        expect("unsupported", 422, "Resume cannot be read", "Save it as PDF.");
        expect("criterion", 422, "Invalid criterion", "Unknown status");
        expect("iae", 422, "Invalid input", "bad");
    }

    @Test
    void responseStatusExceptionIsAProblemDetail() throws Exception {
        expect("rse", 413, "Payload Too Large", "Too big");
    }

    @Test
    void badPathValueAndMissingParameterAreClientErrors() throws Exception {
        mvc.perform(get("/uuid/not-a-uuid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Bad Request"));
        mvc.perform(get("/param")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void unsupportedMediaTypeIs415WithProblemBody() throws Exception {
        mvc.perform(post("/api/import/vacancies").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"));
    }

    @Test
    void unexpectedErrorsAre500WithoutInternals() throws Exception {
        mvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Unexpected error"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void importReadsTheDeclaredCharsetAndDefaultsToUtf8() throws Exception {
        mvc.perform(post("/api/import/vacancies").contentType("text/csv; charset=windows-1251")
                .content("Привіт".getBytes(java.nio.charset.Charset.forName("windows-1251")))).andExpect(status().isOk());
        verify(importer).importCsv("Привіт");
        mvc.perform(post("/api/import/vacancies").contentType("text/csv").content("é".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        verify(importer).importCsv("é");
    }

    @Test
    void importRefusesAnUnknownCharsetWith415() throws Exception {
        mvc.perform(post("/api/import/vacancies").contentType("text/csv; charset=nonsense").content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"));
        org.mockito.Mockito.verify(importer, org.mockito.Mockito.never()).importCsv(anyString());
    }
}
