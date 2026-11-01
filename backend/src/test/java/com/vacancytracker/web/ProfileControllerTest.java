package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private static final String PROFILE = """
            {"name":"Example profile","enabled":true,"sources":["all"],"scheduleOverride":null,
             "thresholds":{"inboxMinScore":40,"notifyMinScore":70,"maxAgeDays":60},
             "criteria":[
              {"id":"keyword-title","field":"title","operator":"contains_any","value":["example role a"],
               "mode":"require","weight":5,"missingValuePolicy":"hide","label":"Keyword in title"},
              {"id":"salary-floor","field":"salary","operator":"gte",
               "value":{"amount":50000,"currency":"EUR","period":"year","basis":"gross"},
               "mode":"prefer","weight":6,"label":"Salary at least the floor"}]}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE search_profile CASCADE");
    }

    private String create() throws Exception {
        String body = mvc.perform(post("/api/profiles").contentType(JSON).content(PROFILE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void createReadUpdateAndReadAnOldVersion() throws Exception {
        String id = create();

        mvc.perform(get("/api/profiles/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criteria.length()").value(2))
                .andExpect(jsonPath("$.criteria[0].missingValuePolicy").value("hide"))
                .andExpect(jsonPath("$.criteria[1].value.currency").value("EUR"));

        mvc.perform(put("/api/profiles/" + id).contentType(JSON).content(PROFILE.replace("Example profile", "Renamed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.name").value("Renamed"));

        mvc.perform(get("/api/profiles/" + id + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2));

        mvc.perform(get("/api/profiles/" + id + "/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Example profile"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void restoreCreatesANewVersion() throws Exception {
        String id = create();
        mvc.perform(put("/api/profiles/" + id).contentType(JSON).content(PROFILE.replace("Example profile", "Renamed")));

        mvc.perform(post("/api/profiles/" + id + "/versions/1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.name").value("Example profile"));
    }

    @Test
    void invalidProfileReturnsPathErrors() throws Exception {
        String bad = PROFILE.replace("\"operator\":\"gte\"", "\"operator\":\"regex\"");

        mvc.perform(post("/api/profiles").contentType(JSON).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors['criteria[1].operator']").value("'regex' is not allowed for field 'salary'"));
    }

    @Test
    void duplicateNameIsConflictAndUnknownIdIsNotFound() throws Exception {
        create();

        mvc.perform(post("/api/profiles").contentType(JSON).content(PROFILE)).andExpect(status().isConflict());
        mvc.perform(get("/api/profiles/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
        mvc.perform(get("/api/profiles/00000000-0000-0000-0000-000000000000/versions")).andExpect(status().isNotFound());
    }
}
