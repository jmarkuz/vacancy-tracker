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
    void metaOffersFieldsOperatorsWithValueShapesModesAndPolicies() throws Exception {
        mvc.perform(get("/api/profiles/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.length()").value(19))
                .andExpect(jsonPath("$.fields[0].name").value("title"))
                .andExpect(jsonPath("$.fields[12].name").value("salary"))
                .andExpect(jsonPath("$.fields[12].operators[?(@.name == 'gte')].valueShape")
                        .value(org.hamcrest.Matchers.hasItem("SALARY")))
                .andExpect(jsonPath("$.modes[1]").value("prefer"))
                .andExpect(jsonPath("$.missingValuePolicies[2]").value("hide"))
                .andExpect(jsonPath("$.minWeight").value(1))
                .andExpect(jsonPath("$.maxWeight").value(10))
                .andExpect(jsonPath("$.defaultThresholds.inboxMinScore").value(40));
    }

    @Test
    void validateIsADryRunThatNeverStoresAnything() throws Exception {
        mvc.perform(post("/api/profiles/validate").contentType(JSON).content(PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.profile.criteria[1].missingValuePolicy").value("ignore"));

        String bad = PROFILE.replace("\"operator\":\"gte\"", "\"operator\":\"regex\"").replace("\"weight\":6", "\"weight\":11");
        mvc.perform(post("/api/profiles/validate").contentType(JSON).content(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors['criteria[1].operator']").exists())
                .andExpect(jsonPath("$.errors['criteria[1].weight']").value("Weight must be between 1 and 10"));

        mvc.perform(get("/api/profiles")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void previewCountsExistingVacanciesAndStoresNothing() throws Exception {
        template.execute("TRUNCATE vacancy CASCADE");
        mvc.perform(post("/api/vacancies").contentType(JSON).content("{\"title\":\"Example role a\",\"company\":\"Acme\"}"));
        mvc.perform(post("/api/vacancies").contentType(JSON).content("{\"title\":\"Something else\",\"company\":\"Acme\"}"));

        mvc.perform(post("/api/profiles/preview").contentType(JSON).content(PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.applicable").value(2))
                .andExpect(jsonPath("$.samples.length()").isNumber())
                .andExpect(jsonPath("$.samples[0].kind").exists());

        mvc.perform(get("/api/profiles")).andExpect(jsonPath("$.length()").value(0));
        org.assertj.core.api.Assertions.assertThat(template.queryForObject("SELECT count(*) FROM vacancy_match", Integer.class)).isZero();
    }

    @Test
    void exportAndImportOverHttp() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/profiles").contentType(JSON).content(PROFILE))
                .andReturn().getResponse().getContentAsString(), "$.id");

        String yaml = mvc.perform(get("/api/profiles/" + id + "/export"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".yml")))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/profiles/" + id + "/export?format=json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.profile.name").exists());

        mvc.perform(post("/api/profiles/import").contentType("text/plain").content(yaml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.profile.version").value(2));
        mvc.perform(post("/api/profiles/import").contentType("text/plain").content("schemaVersion: 9\nprofile: {}\n"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.schemaVersion").exists());
    }

    @Test
    void previewOfAnInvalidDraftIsUnprocessable() throws Exception {
        String bad = PROFILE.replace("\"operator\":\"gte\"", "\"operator\":\"regex\"");

        mvc.perform(post("/api/profiles/preview").contentType(JSON).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors['criteria[1].operator']").exists());
    }

    @Test
    void anUnknownPropertyIsRejectedWithItsPathInsteadOfBeingIgnored() throws Exception {
        String typo = PROFILE.replace("\"weight\":5", "\"weigth\":5");

        mvc.perform(post("/api/profiles").contentType(JSON).content(typo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors['criteria[0].weigth']").value("Unknown property"));
        mvc.perform(post("/api/profiles/validate").contentType(JSON).content(typo))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors['criteria[0].weigth']").value("Unknown property"));
        mvc.perform(get("/api/profiles")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aWrongTypeIsReportedAtItsPathAndBrokenJsonIsABadRequest() throws Exception {
        String wrongType = PROFILE.replace("\"weight\":5", "\"weight\":\"heavy\"");

        mvc.perform(post("/api/profiles").contentType(JSON).content(wrongType))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors['criteria[0].weight']").value("Wrong type or value"));
        mvc.perform(post("/api/profiles").contentType(JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request body"));
    }

    @Test
    void duplicateNameIsConflictAndUnknownIdIsNotFound() throws Exception {
        create();

        mvc.perform(post("/api/profiles").contentType(JSON).content(PROFILE)).andExpect(status().isConflict());
        mvc.perform(get("/api/profiles/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
        mvc.perform(get("/api/profiles/00000000-0000-0000-0000-000000000000/versions")).andExpect(status().isNotFound());
    }
}
