package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.vacancytracker.support.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class VacancyFilterApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void seed() throws Exception {
        create("Java Developer", "Acme", "Saved", "{\"tags\":[\"dream\"],\"priority\":1}");
        create("Python Analyst", "Beta", null, "{}");
        create("Java Architect", "Acme", "Applied", "{}");
    }

    @Test
    void keywordFilterMatchesTitleOrDescription() throws Exception {
        mvc.perform(get("/api/vacancies").param("filter", filter("{\"field\":\"keywords\",\"operator\":\"contains_any\",\"value\":[\"java\"]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void visaSponsorshipIsFilterableAsOfferedRefusedOrUnstated() throws Exception {
        mvc.perform(post("/api/vacancies").contentType(JSON).content(
                "{\"title\":\"Go Engineer\",\"company\":\"Gamma\",\"description\":\"Visa sponsorship available.\"}"));
        mvc.perform(post("/api/vacancies").contentType(JSON).content(
                "{\"title\":\"Go Lead\",\"company\":\"Delta\",\"description\":\"We cannot sponsor visas.\"}"));

        mvc.perform(get("/api/vacancies").param("filter", filter("{\"field\":\"visaSponsorship\",\"operator\":\"equals\",\"value\":true}")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].company").value("Gamma"))
                .andExpect(jsonPath("$.items[0].visaSponsorship").value(true));
        mvc.perform(get("/api/vacancies").param("filter", filter("{\"field\":\"visaSponsorship\",\"operator\":\"equals\",\"value\":false}")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].company").value("Delta"));
        mvc.perform(get("/api/vacancies").param("filter", filter("{\"field\":\"visaSponsorship\",\"operator\":\"not_exists\"}")))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/vacancies").param("filter", filter("{\"field\":\"visaSponsorship\",\"operator\":\"equals\",\"value\":\"yes\"}")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void excludeModeRemovesMatches() throws Exception {
        mvc.perform(get("/api/vacancies").param("filter",
                        filter("{\"field\":\"company\",\"operator\":\"equals\",\"value\":\"Acme\",\"mode\":\"exclude\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].company").value("Beta"));
    }

    @Test
    void combinedCriteriaAndStatusShorthandAndSort() throws Exception {
        mvc.perform(get("/api/vacancies")
                        .param("status", "Saved")
                        .param("filter", filter("{\"field\":\"tags\",\"operator\":\"contains_all\",\"value\":[\"dream\"]}"))
                        .param("sort", "title:asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Java Developer"));

        mvc.perform(get("/api/vacancies").param("sort", "title:asc"))
                .andExpect(jsonPath("$.items[0].title").value("Java Architect"))
                .andExpect(jsonPath("$.items[2].title").value("Python Analyst"));
    }

    @Test
    void countryFilterUsesStoredCountry() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                .content("{\"title\":\"Remote Dev\",\"company\":\"Gamma\",\"location\":\"Berlin\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(id).isNotBlank();
        mvc.perform(get("/api/vacancies").param("filter", filter("{\"field\":\"country\",\"operator\":\"exists\"}")))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void existsAndPriorityFilters() throws Exception {
        mvc.perform(get("/api/vacancies").param("filter",
                        filter("{\"field\":\"priority\",\"operator\":\"gte\",\"value\":1}")))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/vacancies").param("filter",
                        filter("{\"field\":\"nextActionAt\",\"operator\":\"exists\"}")))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void invalidFilterOrSortIsUnprocessable() throws Exception {
        mvc.perform(get("/api/vacancies").param("filter", filter("{\"field\":\"city\",\"operator\":\"equals\",\"value\":\"DE\"}")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/vacancies").param("sort", "salary:up"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/vacancies").param("filter", "not-json"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void savedViewCrudAndApply() throws Exception {
        String body = "{\"name\":\"Acme roles\",\"criteria\":["
                + "{\"field\":\"company\",\"operator\":\"equals\",\"value\":\"Acme\"}],"
                + "\"sort\":\"title:asc\",\"visibleColumns\":[\"title\",\"company\"]}";
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/views").contentType(JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/views/" + id + "/vacancies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Java Architect"));

        mvc.perform(post("/api/views").contentType(JSON).content(body))
                .andExpect(status().isConflict());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/views/" + id)
                        .contentType(JSON).content("{\"name\":\"Acme roles\",\"criteria\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criteria.length()").value(0));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/views/" + id))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/views/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void savedViewWithInvalidCriteriaIsRejected() throws Exception {
        mvc.perform(post("/api/views").contentType(JSON)
                        .content("{\"name\":\"bad\",\"criteria\":[{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"x\",\"mode\":\"avoid\"}]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private void create(String title, String company, String statusLabel, String extra) throws Exception {
        String body = extra.equals("{}")
                ? "{\"title\":\"" + title + "\",\"company\":\"" + company + "\"}"
                : "{\"title\":\"" + title + "\",\"company\":\"" + company + "\",\"description\":\"java stuff\"}";
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON).content(body))
                .andReturn().getResponse().getContentAsString(), "$.id");
        if (extra.contains("tags")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/vacancies/" + id)
                    .contentType(JSON).content("{\"tags\":[\"dream\"],\"priority\":1}"));
        }
        if (statusLabel != null) {
            mvc.perform(post("/api/vacancies/" + id + "/status").contentType(JSON)
                    .content("{\"status\":\"" + statusLabel + "\"}"));
        }
    }

    private static String filter(String criteriaJson) {
        return "[" + criteriaJson + "]";
    }
}
