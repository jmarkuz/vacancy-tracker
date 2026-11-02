package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.application.ai.FakeAiClient;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Employer match, Goal match and their caches (T-095), with the fake AI only. */
@SpringBootTest(properties = "app.ai.provider=fake")
@AutoConfigureMockMvc
class AnalysisApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    /** Java and PostgreSQL have proof in experience; Kafka is only in Skills; Kubernetes is nowhere. */
    static final String BASE_RESUME = "# Jane Doe\\nKyiv | jane@example.com\\n\\n## Experience\\n\\n### Backend Engineer, Acme, Remote\\n"
            + "Jan 2020 to Present\\n- Cut payment latency by 40% with Java and PostgreSQL.\\n\\n## Skills\\nJava, PostgreSQL, Kafka";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeAiClient fake;

    private String preset;
    private String vacancy;

    @BeforeEach
    void seed() throws Exception {
        preset = JsonPath.read(mvc.perform(get("/api/presets")).andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(patch("/api/presets/" + preset).contentType(JSON).content(
                "{\"mainGoal\":\"Senior backend roles, remote.\\nDeal-breakers: on-site only\","
                        + "\"baseCoverLetter\":\"Dear Hiring Manager,\\n\\nI build payment systems with Java.\"}"));
        mvc.perform(post("/api/presets/" + preset + "/base-resume/text").contentType(JSON).content("{\"text\":\"" + BASE_RESUME + "\"}"));
        vacancy = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON).content(
                        "{\"title\":\"Senior Java Engineer\",\"company\":\"Beta Bank\",\"description\":"
                                + "\"We use Java, PostgreSQL, Kafka, Kubernetes.\\nThe role is on-site only.\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String analysis() throws Exception {
        return mvc.perform(post("/api/vacancies/" + vacancy + "/analysis")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String match(String text) throws Exception {
        return mvc.perform(post("/api/vacancies/" + vacancy + "/match").contentType(JSON).content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String statusOf(String match, String requirement) {
        return JsonPath.<List<String>>read(match, "$.items[?(@.text == 'Experience with " + requirement + "')].status").get(0);
    }

    @Test
    void withoutAiOnlyTheQuickFitIsShownAndNothingIsSent() throws Exception {
        int before = fake.calls();
        mvc.perform(post("/api/vacancies/" + vacancy + "/analysis"))
                .andExpect(jsonPath("$.aiEnabled").value(false))
                .andExpect(jsonPath("$.quickFit.score").isNumber())
                .andExpect(jsonPath("$.checklistState").value("none"))
                .andExpect(jsonPath("$.baseResume").doesNotExist());
        mvc.perform(post("/api/vacancies/" + vacancy + "/match").contentType(JSON).content("{\"text\":\"Java\"}"))
                .andExpect(status().isConflict());
        assertThat(fake.calls()).isEqualTo(before);
    }

    @Test
    void theChecklistTheScoresAndTheGoalAreComputedOnceAndCached() throws Exception {
        mvc.perform(post("/api/ai/consent"));
        int before = fake.calls();
        String first = analysis();

        assertThat(JsonPath.<String>read(first, "$.checklistState")).isEqualTo("current");
        assertThat(JsonPath.<List<String>>read(first, "$.checklist.requirements[*].priority")).containsExactly("must", "must", "must", "important");
        // Java and PostgreSQL covered (3 + 3), Kafka only in Skills: partial (3 × 0.5), Kubernetes missing (2 × 0): 7.5 / 11 → 68.
        assertThat(JsonPath.<Integer>read(first, "$.baseResume.match.score")).isEqualTo(68);
        assertThat(JsonPath.<List<String>>read(first, "$.baseResume.match.items[?(@.text == 'Experience with Kafka')].status")).containsExactly("partial");
        assertThat(JsonPath.<Integer>read(first, "$.goal.score")).isEqualTo(70);
        assertThat(JsonPath.<Boolean>read(first, "$.goal.dealBreaker")).isTrue();
        assertThat(JsonPath.<String>read(first, "$.goal.dealBreakerReason")).contains("on-site only");
        assertThat(JsonPath.<Double>read(first, "$.goal.parameters[0].share")).isEqualTo(0.3);
        int afterFirst = fake.calls();
        assertThat(afterFirst - before).isEqualTo(4); // checklist, goal, base resume, base cover letter

        // The same texts give the same scores, from the cache: no more calls.
        String second = analysis();
        for (String part : List.of("$.checklist", "$.goal", "$.baseResume", "$.baseCoverLetter")) {
            assertThat(JsonPath.<Object>read(second, part)).as(part).isEqualTo(JsonPath.read(first, part));
        }
        mvc.perform(get("/api/vacancies/" + vacancy + "/analysis")).andExpect(jsonPath("$.baseResume.match.score").value(68));
        assertThat(fake.calls()).isEqualTo(afterFirst);
    }

    @Test
    void aKeywordWithoutProofIsNeverCovered() throws Exception {
        mvc.perform(post("/api/ai/consent"));
        String inSkillsOnly = match("## Experience\\n- Built payment services.\\n\\n## Skills\\nKubernetes, Java");
        assertThat(statusOf(inSkillsOnly, "Kubernetes")).isEqualTo("partial");
        assertThat(JsonPath.<List<String>>read(inSkillsOnly, "$.items[?(@.text == 'Experience with Kubernetes')].note").get(0)).contains("only in Skills");
        String withProof = match("## Experience\\n- Ran Kubernetes clusters for payment services.\\n\\n## Skills\\nJava");
        assertThat(statusOf(withProof, "Kubernetes")).isEqualTo("covered");
        assertThat(statusOf(match("## Experience\\n- Built payment services."), "Kubernetes")).isEqualTo("missing");

        // An unchanged text keeps its score, call after call.
        int calls = fake.calls();
        assertThat(match("## Experience\\n- Ran Kubernetes clusters for payment services.\\n\\n## Skills\\nJava")).isEqualTo(withProof);
        assertThat(fake.calls()).isEqualTo(calls);
    }

    @Test
    void theTailoredResumeScoresHigherOnTheSameChecklist() throws Exception {
        mvc.perform(post("/api/ai/consent"));
        String first = analysis();
        int base = JsonPath.read(first, "$.baseResume.match.score");
        List<Object> checklist = JsonPath.read(first, "$.checklist.requirements");

        String draft = JsonPath.read(mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON)
                .content("{\"kind\":\"resume\"}")).andReturn().getResponse().getContentAsString(), "$.content");
        String tailored = match(draft.replace("\n", "\\n"));

        int tailoredScore = JsonPath.read(tailored, "$.score");
        assertThat(tailoredScore).as("Base %d%% → Tailored %d%%", base, tailoredScore).isGreaterThan(base);
        assertThat(statusOf(tailored, "Kubernetes")).isEqualTo("covered");
        assertThat(JsonPath.<List<Object>>read(analysis(), "$.checklist.requirements")).isEqualTo(checklist);
    }

    @Test
    void savingAnEditedVersionScoresThatVersion() throws Exception {
        mvc.perform(post("/api/ai/consent"));
        assertThat(JsonPath.<Object>read(analysis(), "$.resume")).isNull();

        String edited = "## Experience\\n- Ran Kubernetes clusters with Java and PostgreSQL.\\n\\n## Skills\\nKafka";
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON)
                .content("{\"kind\":\"resume\",\"content\":\"" + edited + "\",\"origin\":\"manual\"}")).andExpect(status().isOk());

        String after = analysis();
        assertThat(JsonPath.<Integer>read(after, "$.resume.version")).isEqualTo(1);
        // Java, PostgreSQL, Kubernetes covered (3 + 3 + 2), Kafka in Skills only (3 × 0.5): 9.5 / 11 → 86.
        assertThat(JsonPath.<Integer>read(after, "$.resume.match.score")).isEqualTo(86);
        assertThat(JsonPath.<Integer>read(after, "$.baseResume.match.score")).isEqualTo(68);
        assertThat(JsonPath.<Integer>read(match(edited), "$.score")).isEqualTo(86);
    }

    @Test
    void aChangedVacancyTextResetsTheChecklistAndMarksTheGoalStale() throws Exception {
        mvc.perform(post("/api/ai/consent"));
        analysis();

        mvc.perform(patch("/api/vacancies/" + vacancy).contentType(JSON).content("{\"description\":\"We use Golang, Redis, Terraform.\"}"));

        mvc.perform(get("/api/vacancies/" + vacancy + "/analysis"))
                .andExpect(jsonPath("$.checklistState").value("stale"))
                .andExpect(jsonPath("$.checklist").doesNotExist())
                .andExpect(jsonPath("$.baseResume").doesNotExist())
                .andExpect(jsonPath("$.goalState").value("stale"));
        String fresh = analysis();
        assertThat(JsonPath.<String>read(fresh, "$.checklistState")).isEqualTo("current");
        assertThat(JsonPath.<List<String>>read(fresh, "$.checklist.requirements[*].text")).containsExactly("Experience with Golang", "Experience with Redis", "Experience with Terraform");
        assertThat(JsonPath.<String>read(fresh, "$.goalState")).isEqualTo("current");
        assertThat(JsonPath.<Boolean>read(fresh, "$.goal.dealBreaker")).isFalse();
    }
}
