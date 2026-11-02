package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The fact check before saving (T-098), with the fake AI only. */
@SpringBootTest(properties = "app.ai.provider=fake")
@AutoConfigureMockMvc
class FactCheckApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "# Jane Doe\nKyiv | jane@example.com\n\n## Experience\n### Backend Engineer, Acme, Remote\n"
            + "Jan 2020 to Present\n- Cut payment latency by 40% with Java and PostgreSQL.\n\n## Skills\nJava, PostgreSQL";

    @Autowired
    private MockMvc mvc;

    private String vacancy;

    @BeforeEach
    void seed() throws Exception {
        String preset = JsonPath.read(mvc.perform(get("/api/presets")).andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(patch("/api/presets/" + preset).contentType(JSON).content("{\"mainGoal\":\"Backend roles.\"}"));
        mvc.perform(post("/api/presets/" + preset + "/base-resume/text").contentType(JSON).content("{\"text\":" + MAPPER.writeValueAsString(BASE) + "}"));
        vacancy = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                        .content("{\"title\":\"Senior Java Engineer\",\"company\":\"Beta Bank\",\"description\":\"We use Java, Kafka, Kubernetes.\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/ai/consent"));
    }

    private String save(String content, String snapshot, Boolean confirmed) throws Exception {
        return "{\"kind\":\"resume\",\"origin\":\"ai\",\"content\":" + MAPPER.writeValueAsString(content) + ",\"snapshotId\":\"" + snapshot + "\""
                + (confirmed == null ? "" : ",\"factsConfirmed\":" + confirmed) + "}";
    }

    @Test
    void newFactsBlockSavingAndThePdfUntilFixedOrConfirmed() throws Exception {
        String draft = mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andReturn().getResponse().getContentAsString();
        String content = JsonPath.read(draft, "$.content");
        String snapshot = JsonPath.read(draft, "$.snapshotId");
        // The fake adds a bullet with two skills the base resume does not have: the check names them.
        assertThat(JsonPath.<List<String>>read(draft, "$.facts[?(@.category == 'technology')].value")).containsExactly("Kafka", "Kubernetes");
        assertThat(JsonPath.<String>read(draft, "$.facts[0].line")).isEqualTo("- Built production services with Kafka and Kubernetes.");

        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON).content(save(content, snapshot, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Kafka (technology), Kubernetes (technology)")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("confirm these facts are true")));
        mvc.perform(get("/api/vacancies/" + vacancy + "/documents")).andExpect(jsonPath("$.length()").value(0));

        // Confirmed: saved with its PDF, and the confirmation is kept in the version.
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON).content(save(content, snapshot, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factsConfirmed").value(true))
                .andExpect(jsonPath("$.factFindings.length()").value(2))
                .andExpect(jsonPath("$.pdfPages").value(1));

        // Fixed: the invented bullet removed, the check is clean and no confirmation is needed.
        String fixed = content.lines().filter(l -> !l.startsWith("- Built production services")).reduce((a, b) -> a + "\n" + b).orElseThrow();
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/facts").contentType(JSON)
                        .content("{\"snapshotId\":\"" + snapshot + "\",\"text\":" + MAPPER.writeValueAsString(fixed) + "}"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON).content(save(fixed, snapshot, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.factsConfirmed").value(false))
                .andExpect(jsonPath("$.factFindings.length()").value(0));
    }

    @Test
    void anEditOfTheBaseDraftThatChangesANumberIsCaught() throws Exception {
        String draft = mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andReturn().getResponse().getContentAsString();
        String snapshot = JsonPath.read(draft, "$.snapshotId");
        String inflated = BASE.replace("by 40%", "by 75%").replace("Jan 2020", "Jan 2015");

        String facts = mvc.perform(post("/api/vacancies/" + vacancy + "/documents/facts").contentType(JSON)
                        .content("{\"snapshotId\":\"" + snapshot + "\",\"text\":" + MAPPER.writeValueAsString(inflated) + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(facts, "$[*].value")).containsExactlyInAnyOrder("75%", "Jan 2015");
    }
}
