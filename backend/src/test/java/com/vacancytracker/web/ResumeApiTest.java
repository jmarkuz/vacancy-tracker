package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.application.fit.ResumeFitService;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ResumeApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    static final String RESUME = """
            Jane Doe, Backend Engineer

            Skills
            Java, Spring Boot, PostgreSQL, Kafka

            Experience
            Acme, 2018 - 2025: payment services in Java.
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ResumeFitService fits;

    @Autowired
    private JdbcTemplate jdbc;

    private String vacancy(String title, String description) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                        .content("{\"title\":\"" + title + "\",\"company\":\"Acme\",\"description\":\"" + description + "\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void withoutAResumeTheFitSaysWhatToDo() throws Exception {
        String id = vacancy("Java Engineer", "Java and Spring Boot.");

        mvc.perform(get("/api/resume")).andExpect(status().isNotFound());
        mvc.perform(get("/api/vacancies/" + id + "/fit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Upload a resume to see how vacancies fit it."));
        mvc.perform(get("/api/resume/fits").param("ids", id)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anUploadedFileBecomesTheResumeAndScoresEveryVacancyWithReasons() throws Exception {
        String java = vacancy("Senior Java Engineer", "Requirements: Java, Spring Boot, Kubernetes, AWS. 5+ years.");
        String design = vacancy("Product Designer", "Figma, Sketch, Miro.");

        mvc.perform(post("/api/resume/file").param("fileName", "jane.md").contentType("text/markdown")
                        .content(RESUME.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("jane"))
                .andExpect(jsonPath("$.keywords[0]").value("Java"))
                .andExpect(jsonPath("$.years").value(7))
                .andExpect(jsonPath("$.hasOriginal").value(true));

        mvc.perform(get("/api/vacancies/" + java + "/fit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched[0]").value("Java"))
                .andExpect(jsonPath("$.missing").isNotEmpty())
                .andExpect(jsonPath("$.reasons[0].kind").value("matched"));
        String body = mvc.perform(get("/api/resume/fits").param("ids", java + "," + design))
                .andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(body, "$[*].vacancyId");
        java.util.List<Integer> scores = JsonPath.read(body, "$[*].score");
        assertThat(scores.get(ids.indexOf(java))).isGreaterThan(scores.get(ids.indexOf(design)));

        mvc.perform(get("/api/resume/original"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("jane.md")));
    }

    @Test
    void editingKeywordsRaisesTheRevisionAndTheFitFollows() throws Exception {
        String id = vacancy("Go Developer", "Go, gRPC and Kubernetes.");
        mvc.perform(post("/api/resume/text").contentType(JSON).content("{\"name\":\"Mine\",\"text\":\"Java developer.\"}"))
                .andExpect(jsonPath("$.hasOriginal").value(false))
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get("/api/vacancies/" + id + "/fit")).andExpect(jsonPath("$.score").value(0));

        mvc.perform(put("/api/resume/keywords").contentType(JSON).content("{\"keywords\":[\"Go\",\" go \",\"gRPC\",\"Kubernetes\",\"\"]}"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.keywords.length()").value(3));

        mvc.perform(get("/api/vacancies/" + id + "/fit"))
                .andExpect(jsonPath("$.resumeRevision").value(2))
                .andExpect(jsonPath("$.score").value(100));
        mvc.perform(get("/api/resume/original")).andExpect(status().isNotFound());
    }

    @Test
    void theBackgroundPassComputesStaleFitsAndDeletingTheResumeRemovesThem() throws Exception {
        vacancy("Java Engineer", "Java.");
        vacancy("Kafka Engineer", "Kafka.");
        mvc.perform(post("/api/resume/text").contentType(JSON).content("{\"text\":\"Java, Kafka\"}"));

        assertThat(fits.recalculateStale()).isLessThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_fit", Integer.class)).isEqualTo(2);
        assertThat(fits.recalculateStale()).isZero();
        mvc.perform(post("/api/resume/recalculate")).andExpect(status().isAccepted());

        mvc.perform(delete("/api/resume")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_fit", Integer.class)).isZero();
    }

    @Test
    void unreadableUploadsAndBadRequestsAreExplained() throws Exception {
        mvc.perform(post("/api/resume/file").param("fileName", "cv.exe").content(new byte[] {1, 2, 3}))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Use a PDF, DOCX, TXT or MD file."));
        mvc.perform(post("/api/resume/text").contentType(JSON).content("{\"text\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/resume/fits").param("ids", "not-a-uuid")).andExpect(status().isUnprocessableEntity());
    }
}
