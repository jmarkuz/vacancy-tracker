package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class VacancyControllerTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @Test
    void createReadUpdateAndListVacancy() throws Exception {
        MvcResult created = mvc.perform(post("/api/vacancies").contentType(JSON)
                        .content("""
                                {"title":"Backend Engineer","company":"Northwind",
                                 "url":"https://Example.com/jobs/1/?utm_source=x","description":"Java"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("New"))
                .andExpect(jsonPath("$.canonicalUrl").value("https://example.com/jobs/1"))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(patch("/api/vacancies/" + id).contentType(JSON)
                        .content("{\"notes\":\"call back\",\"priority\":2,\"tags\":[\"dream\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("call back"))
                .andExpect(jsonPath("$.priority").value(2))
                .andExpect(jsonPath("$.tags[0]").value("dream"));

        mvc.perform(post("/api/vacancies/" + id + "/status").contentType(JSON)
                        .content("{\"status\":\"Saved\",\"note\":\"good\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Saved"));

        mvc.perform(get("/api/vacancies/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].from").value("New"))
                .andExpect(jsonPath("$[0].to").value("Saved"))
                .andExpect(jsonPath("$[0].note").value("good"));

        mvc.perform(get("/api/vacancies").param("status", "Saved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void editingDescriptionMarksFieldAsUserEdited() throws Exception {
        String id = createOne("Data Analyst", "Helio");

        mvc.perform(patch("/api/vacancies/" + id).contentType(JSON)
                        .content("{\"description\":\"my own summary\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userEditedFields[0]").value("description"))
                .andExpect(jsonPath("$.description").value("my own summary"));
    }

    @Test
    void invalidTransitionIsConflict() throws Exception {
        String id = createOne("Platform Engineer", "Kestrel");

        mvc.perform(post("/api/vacancies/" + id + "/status").contentType(JSON)
                        .content("{\"status\":\"Offer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid status transition"));
    }

    @Test
    void unknownVacancyIsNotFound() throws Exception {
        mvc.perform(get("/api/vacancies/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInputReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/vacancies").contentType(JSON).content("{\"title\":\"\",\"company\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.company").exists());
    }

    @Test
    void listRejectsBadPaging() throws Exception {
        mvc.perform(get("/api/vacancies").param("size", "0"))
                .andExpect(status().isUnprocessableEntity());
    }

    private String createOne(String title, String company) throws Exception {
        MvcResult r = mvc.perform(post("/api/vacancies").contentType(JSON)
                        .content("{\"title\":\"" + title + "\",\"company\":\"" + company + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(r.getResponse().getContentAsString(), "$.id");
        assertThat(id).isNotBlank();
        return id;
    }

    @Test
    void bulkStatusChangeAndUndoOverHttp() throws Exception {
        String a = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON).content("{\"title\":\"Bulk A\",\"company\":\"Acme\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String b = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON).content("{\"title\":\"Bulk B\",\"company\":\"Acme\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        String body = mvc.perform(post("/api/bulk/vacancies/status").contentType(JSON)
                        .content("{\"ids\":[\"" + a + "\",\"" + b + "\",\"" + java.util.UUID.randomUUID() + "\"],\"status\":\"Saved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].reason").value("Not found"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/vacancies/" + a)).andExpect(jsonPath("$.status").value("Saved"));

        mvc.perform(post("/api/bulk/vacancies/undo").contentType(JSON)
                        .content("{\"items\":[{\"id\":\"" + a + "\",\"status\":\"New\",\"expected\":\"Saved\",\"wasNew\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed.length()").value(1));
        mvc.perform(get("/api/vacancies/" + a)).andExpect(jsonPath("$.status").value("New")).andExpect(jsonPath("$.isNew").value(true));
        mvc.perform(get("/api/vacancies/" + b)).andExpect(jsonPath("$.status").value("Saved"));
        assertThat(body).contains("\"from\":\"New\"");

        mvc.perform(post("/api/bulk/vacancies/status").contentType(JSON).content("{\"ids\":[],\"status\":\"Saved\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/bulk/vacancies/status").contentType(JSON).content("{\"ids\":[\"" + a + "\"],\"status\":\"Nonsense\"}"))
                .andExpect(status().is4xxClientError());
    }
}
