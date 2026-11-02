package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PresetApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static String resource(String name) throws IOException {
        try (InputStream in = PresetApiTest.class.getResourceAsStream("/presets/default/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        }
    }

    private String list() throws Exception {
        return mvc.perform(get("/api/presets")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String create(String name) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/presets").contentType(JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void theFirstRunHasADefaultPresetFilledWithTheDefaultsWordForWord() throws Exception {
        String body = list();

        assertThat(JsonPath.<Integer>read(body, "$.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(body, "$[0].name")).isEqualTo("Default");
        assertThat(JsonPath.<Boolean>read(body, "$[0].isDefault")).isTrue();
        assertThat(JsonPath.<String>read(body, "$[0].mainGoal")).isEqualTo(resource("main-goal-prompt.md"))
                .startsWith("# Job search goal").contains("Target roles: {{for example, Senior Backend Engineer}}");
        assertThat(JsonPath.<String>read(body, "$[0].resumePrompt")).isEqualTo(resource("resume-prompt.md"));
        assertThat(JsonPath.<String>read(body, "$[0].coverLetterPrompt")).isEqualTo(resource("cover-letter-prompt.md"));
        assertThat(JsonPath.<String>read(body, "$[0].baseCoverLetter")).isEqualTo(resource("base-cover-letter.md"));
        assertThat(JsonPath.<String>read(body, "$[0].baseResume.text")).isEqualTo(resource("base-resume.md"));
        assertThat(JsonPath.<Boolean>read(body, "$[0].baseResume.template")).isTrue();
        assertThat(JsonPath.<List<String>>read(body, "$[0].weights[*].name")).containsExactly("Role and scope", "Technology stack",
                "Seniority and growth", "Compensation", "Location and work mode", "Company and domain");
        assertThat(JsonPath.<List<Integer>>read(body, "$[0].weights[*].weight")).containsExactly(30, 20, 15, 15, 10, 10);
        // Placeholders keep both documents from being generated, and the screen is told what to fill in.
        assertThat(JsonPath.<List<String>>read(body, "$[0].readiness.resume[*].field")).contains("mainGoal", "baseResume");
        assertThat(JsonPath.<List<String>>read(body, "$[0].readiness.coverLetter[*].field")).contains("mainGoal", "baseCoverLetter");
        assertThat(JsonPath.<List<String>>read(body, "$[0].readiness.resume[0].placeholders")).contains("level");

        mvc.perform(get("/api/presets/defaults"))
                .andExpect(jsonPath("$.guardrail").value(resource("guardrail.md")))
                .andExpect(jsonPath("$.weights.length()").value(6));
    }

    @Test
    void anExistingResumeBecomesTheDefaultPresetsBaseResumeAndBothScreensShareIt() throws Exception {
        String resume = JsonPath.read(mvc.perform(post("/api/resume/text").contentType(JSON)
                .content("{\"text\":\"Jane Doe, Java engineer\"}")).andReturn().getResponse().getContentAsString(), "$.id");

        String body = list();
        assertThat(JsonPath.<String>read(body, "$[0].baseResume.resumeId")).isEqualTo(resume);
        assertThat(JsonPath.<Boolean>read(body, "$[0].baseResume.template")).isFalse();
        String preset = JsonPath.read(body, "$[0].id");

        // Pasting on the preset changes the Resume screen, and uploading on the Resume screen changes the preset.
        mvc.perform(post("/api/presets/" + preset + "/base-resume/text").contentType(JSON).content("{\"text\":\"Jane Doe, Kotlin engineer\"}"))
                .andExpect(jsonPath("$.baseResume.text").value("Jane Doe, Kotlin engineer"));
        mvc.perform(get("/api/resume")).andExpect(jsonPath("$.text").value("Jane Doe, Kotlin engineer"));
        mvc.perform(post("/api/resume/file").param("fileName", "jane.md").contentType("text/markdown")
                .content("# Jane Doe\n\nGo engineer".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        mvc.perform(get("/api/presets/" + preset)).andExpect(jsonPath("$.baseResume.fileName").value("jane.md"))
                .andExpect(jsonPath("$.baseResume.hasOriginal").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resume WHERE active", Integer.class)).isEqualTo(1);
    }

    @Test
    void presetsAreCreatedDuplicatedRenamedMadeDefaultAndDeletedButNeverTheLastOne() throws Exception {
        String defaultId = JsonPath.read(list(), "$[0].id");
        String remote = create("Remote Java");
        mvc.perform(post("/api/presets").contentType(JSON).content("{\"name\":\"remote java\"}"))
                .andExpect(status().isUnprocessableEntity());

        String copy = JsonPath.read(mvc.perform(post("/api/presets/" + remote + "/duplicate"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Remote Java copy"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(patch("/api/presets/" + copy).contentType(JSON).content("{\"name\":\"Contract roles\"}"))
                .andExpect(jsonPath("$.name").value("Contract roles"));

        mvc.perform(post("/api/presets/" + remote + "/default")).andExpect(jsonPath("$.isDefault").value(true));
        assertThat(JsonPath.<List<String>>read(list(), "$[?(@.isDefault == true)].id")).containsExactly(remote);

        mvc.perform(delete("/api/presets/" + remote)).andExpect(status().isNoContent());
        assertThat(JsonPath.<List<Boolean>>read(list(), "$[*].isDefault")).containsExactly(true, false);
        mvc.perform(delete("/api/presets/" + copy)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/presets/" + defaultId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("The last preset cannot be deleted."));
    }

    @Test
    void weightsAreNormalisedAndFilledFieldsMakeThePresetReady() throws Exception {
        String id = JsonPath.read(list(), "$[0].id");

        mvc.perform(patch("/api/presets/" + id).contentType(JSON).content(
                        "{\"weights\":[{\"name\":\"Stack\",\"weight\":3},{\"name\":\"Pay\",\"weight\":1},{\"name\":\"Remote\",\"weight\":0}]}"))
                .andExpect(jsonPath("$.weights[0].share").value(75.0))
                .andExpect(jsonPath("$.weights[1].share").value(25.0))
                .andExpect(jsonPath("$.weights[2].share").value(0.0));
        mvc.perform(patch("/api/presets/" + id).contentType(JSON).content("{\"weights\":[{\"name\":\"Stack\",\"weight\":0}]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/presets/" + id).contentType(JSON)
                        .content("{\"weights\":[{\"name\":\"Stack\",\"weight\":1},{\"name\":\"stack\",\"weight\":2}]}"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(patch("/api/presets/" + id).contentType(JSON).content(
                        "{\"mainGoal\":\"Senior backend roles, remote in the EU.\",\"baseCoverLetter\":\"Dear Hiring Manager, I build payment systems.\"}"))
                .andExpect(jsonPath("$.readiness.coverLetter.length()").value(0))
                .andExpect(jsonPath("$.readiness.resume[0].field").value("baseResume"));
        mvc.perform(post("/api/presets/" + id + "/base-resume/text").contentType(JSON).content("{\"text\":\"Jane Doe, Java engineer\"}"))
                .andExpect(jsonPath("$.readiness.resume.length()").value(0));

        mvc.perform(post("/api/presets/" + id + "/base-cover-letter/file").param("fileName", "letter.txt").contentType("text/plain")
                        .content("Dear team, {{company}} is great.".getBytes(StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.baseCoverLetterFileName").value("letter.txt"))
                .andExpect(jsonPath("$.readiness.coverLetter[0].placeholders[0]").value("company"));

        mvc.perform(delete("/api/presets/" + id + "/base-resume")).andExpect(jsonPath("$.baseResume.template").value(true));
        mvc.perform(get("/api/resume")).andExpect(status().isNotFound());
    }

    @Test
    void presetsGoIntoTheJsonBackupAndComeBackFromIt() throws Exception {
        String id = JsonPath.read(list(), "$[0].id");
        mvc.perform(post("/api/presets/" + id + "/base-resume/file").param("fileName", "jane.md").contentType("text/markdown")
                .content("# Jane Doe\n\nJava engineer".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        mvc.perform(patch("/api/presets/" + id).contentType(JSON).content("{\"mainGoal\":\"Remote Java roles.\"}"));
        String second = create("Contract");
        mvc.perform(post("/api/presets/" + second + "/default"));
        var started = mvc.perform(get("/api/export/backup.json")).andExpect(request().asyncStarted()).andReturn();
        String backup = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<Integer>read(backup, "$.presets.length()")).isEqualTo(2);

        jdbc.execute("TRUNCATE resume CASCADE");
        mvc.perform(post("/api/import/vacancies").contentType(JSON).content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored.presets").value(2));

        String body = list();
        assertThat(JsonPath.<List<String>>read(body, "$[*].name")).containsExactly("Contract", "Default");
        assertThat(JsonPath.<String>read(body, "$[1].mainGoal")).isEqualTo("Remote Java roles.");
        assertThat(JsonPath.<String>read(body, "$[1].baseResume.fileName")).isEqualTo("jane.md");
        assertThat(JsonPath.<Boolean>read(body, "$[1].baseResume.hasOriginal")).isTrue();
        // The restored default has the template, so no resume is active, as before the backup.
        mvc.perform(get("/api/resume")).andExpect(status().isNotFound());
    }
}
