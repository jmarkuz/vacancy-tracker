package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.normalize.Normalizer;
import com.vacancytracker.common.CanonicalUrl;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.AutomatedFields;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Regression tests for the findings of the 2026-11-02 review that were fixed (docs/code-review.md). */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewFixesTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private VacancyRepository vacancies;

    private String create(String body) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void patchingOnlyTheNextActionNoteKeepsItsDate() throws Exception {
        String id = create("{\"title\":\"Role\",\"company\":\"Acme\"}");
        mvc.perform(patch("/api/vacancies/" + id).contentType(JSON)
                .content("{\"nextActionAt\":\"2026-11-05T09:00:00Z\",\"nextActionNote\":\"call\"}")).andExpect(status().isOk());

        mvc.perform(patch("/api/vacancies/" + id).contentType(JSON).content("{\"nextActionNote\":\"call recruiter\"}"))
                .andExpect(jsonPath("$.nextActionAt").value("2026-11-05T09:00:00Z"))
                .andExpect(jsonPath("$.nextActionNote").value("call recruiter"));
        mvc.perform(patch("/api/vacancies/" + id).contentType(JSON).content("{\"nextActionAt\":\"2026-11-06T09:00:00Z\"}"))
                .andExpect(jsonPath("$.nextActionNote").value("call recruiter"));
    }

    @Test
    void aSalaryCorrectedByCsvSurvivesTheNextRun() throws Exception {
        String id = create("{\"title\":\"Role\",\"company\":\"Acme\"}");
        String csv = "id,title,company,salary_min,salary_max,currency,salary_period\r\n" + id + ",Role,Acme,70000,80000,USD,year\r\n";
        mvc.perform(post("/api/import/vacancies").contentType("text/csv").content(csv)).andExpect(jsonPath("$.imported").value(1));

        Vacancy vacancy = Vacancy.restore(vacancies.findById(UUID.fromString(id)).orElseThrow());
        vacancy.applyAutomated(new AutomatedFields(null, new BigDecimal("50000"), new BigDecimal("60000"), "USD", "year", Instant.now()));

        assertThat(vacancy.getUserEditedFields()).contains(Vacancy.FIELD_SALARY);
        assertThat(vacancy.getSalaryMin()).isEqualByComparingTo("70000");
    }

    @Test
    void onlyWebLinksAreStored() throws Exception {
        mvc.perform(post("/api/vacancies").contentType(JSON)
                        .content("{\"title\":\"Role\",\"company\":\"Acme\",\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isUnprocessableEntity());
        String csv = "title,company,url\r\nRole,Acme,javascript:alert(1)\r\n";
        mvc.perform(post("/api/import/vacancies").contentType("text/csv").content(csv))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.errors[0].field").value("url"));

        var posting = Normalizer.normalize(new RawPosting("x", "1", "javascript:alert(1)", "Role", "Acme", null, null, null,
                null, null, null, null, null)).orElseThrow();
        assertThat(posting.url()).isNull();
        assertThat(posting.canonicalUrl()).isNull();
        assertThat(CanonicalUrl.webLink(" https://ex.com/a ")).isEqualTo("https://ex.com/a");
        assertThat(CanonicalUrl.webLink("data:text/html,x")).isNull();
        assertThat(CanonicalUrl.webLink("mailto:a@b.c")).isNull();
        mvc.perform(get("/api/vacancies")).andExpect(jsonPath("$.total").value(0));
    }
}
