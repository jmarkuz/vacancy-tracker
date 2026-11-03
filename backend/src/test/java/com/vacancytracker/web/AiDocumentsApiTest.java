package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The AI opt-in and tailored documents, with the fake provider: nothing leaves the test. */
@SpringBootTest(properties = "app.ai.provider=fake")
@AutoConfigureMockMvc
class AiDocumentsApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String vacancy;

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("TRUNCATE ai_consent");
        vacancy = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                        .content("{\"title\":\"Backend Engineer\",\"company\":\"Acme Pay\",\"description\":\"Java and Kafka.\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** Fills the default preset so both documents can be generated: goal, base cover letter and base resume. */
    private void resume() throws Exception {
        mvc.perform(post("/api/resume/text").contentType(JSON).content("{\"text\":\"Skills: Java, Kafka\"}")).andExpect(status().isOk());
        String preset = JsonPath.read(mvc.perform(get("/api/presets")).andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(patch("/api/presets/" + preset).contentType(JSON)
                        .content("{\"mainGoal\":\"Backend roles.\",\"baseCoverLetter\":\"Dear Hiring Manager,\\n\\nI build payment systems.\"}"))
                .andExpect(status().isOk());
    }

    private String draft(String kind) throws Exception {
        return mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"" + kind + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void nothingIsSentBeforeTheOwnerSwitchesAiOn() throws Exception {
        resume();
        mvc.perform(get("/api/ai/status"))
                .andExpect(jsonPath("$.provider").value("fake"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.consented").value(false))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("Anthropic")));

        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Switch it on in Settings")));

        mvc.perform(post("/api/ai/consent")).andExpect(jsonPath("$.enabled").value(true));
        // The fake adapts the base resume; it already names every skill of this vacancy, so nothing is added.
        assertThat(JsonPath.<String>read(draft("resume"), "$.content")).isEqualTo("Skills: Java, Kafka");

        mvc.perform(delete("/api/ai/consent")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"cover_letter\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aDraftIsOnlyStoredWhenSavedAndEverySaveIsANewVersion() throws Exception {
        mvc.perform(post("/api/ai/consent"));
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is not ready for AI")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Presets screen")));
        resume();

        String letter = draft("cover_letter");
        assertThat(JsonPath.<String>read(letter, "$.content"))
                .isEqualTo("Dear Hiring Manager,\n\nI am applying for the Backend Engineer role at Acme Pay.\n\nI build payment systems.");
        mvc.perform(get("/api/vacancies/" + vacancy + "/documents")).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON)
                        .content("{\"kind\":\"cover_letter\",\"content\":\"Dear Acme Pay team, edited.\",\"origin\":\"ai\",\"provider\":\"fake\",\"model\":\"fake-model\"}"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.origin").value("ai"));
        String second = mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON)
                        .content("{\"kind\":\"cover_letter\",\"content\":\"# Second\\n- **Java** expert\",\"origin\":\"manual\",\"provider\":\"x\"}"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/vacancies/" + vacancy + "/documents"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2));

        String id = JsonPath.read(second, "$.id");
        byte[] docx = mvc.perform(get("/api/documents/" + id + "/download"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("cover-letter-v2-Acme-Pay.docx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx)); XWPFWordExtractor text = new XWPFWordExtractor(doc)) {
            assertThat(text.getText()).contains("Second").contains("• Java expert");
        }
        mvc.perform(get("/api/documents/" + id + "/download").param("format", "md")).andExpect(content().string("# Second\n- **Java** expert"));
        mvc.perform(get("/api/documents/" + id + "/download").param("format", "pdf")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void badRequestsAreRefused() throws Exception {
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON).content("{\"kind\":\"poem\",\"content\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON).content("{\"kind\":\"resume\",\"content\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/documents/" + java.util.UUID.randomUUID() + "/download")).andExpect(status().isNotFound());
    }
}
