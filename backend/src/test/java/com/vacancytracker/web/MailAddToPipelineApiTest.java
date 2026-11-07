package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** "Add to Pipeline" on the postings of a digest mail: idempotent, deduplicated by link, and visible in the list. */
@SpringBootTest
@AutoConfigureMockMvc
class MailAddToPipelineApiTest extends AbstractIntegrationTest {

    private static final String ALPHA = "https://www.linkedin.com/jobs/view/3912345678/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String mailId;

    @BeforeEach
    void importDigest() throws Exception {
        jdbc.execute("DELETE FROM correspondence");
        jdbc.execute("DELETE FROM vacancy WHERE canonical_url LIKE 'https://www.linkedin.com/jobs/view/39%'");
        String html = Files.readString(Path.of("src/test/resources/mail/linkedin-alert.html"), StandardCharsets.UTF_8);
        String eml = "From: LinkedIn <jobs-noreply@linkedin.com>\r\nTo: owner@test.example\r\nSubject: Senior Java roles for you\r\n"
                + "Date: Thu, 05 Nov 2026 09:00:00 +0000\r\nMessage-ID: <add-" + UUID.randomUUID() + "@linkedin.com>\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n"
                + "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nAbout the position and your resume\r\n"
                + "--b1\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" + html + "\r\n--b1--\r\n";
        String outcome = mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mailId = JsonPath.read(outcome, "$.correspondence.id");
    }

    private String add(int index) throws Exception {
        return mvc.perform(post("/api/mail/" + mailId + "/jobs/" + index + "/add")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int vacancyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM vacancy WHERE canonical_url = ?", Integer.class, ALPHA.substring(0, ALPHA.length() - 1));
    }

    @Test
    void addingCreatesANewVacancyFromThePostingAndAddingAgainReturnsTheSameOne() throws Exception {
        String first = add(0);
        String second = add(0);

        assertThat(JsonPath.<Boolean>read(first, "$.created")).isTrue();
        assertThat(JsonPath.<Boolean>read(second, "$.created")).isFalse();
        assertThat(JsonPath.<String>read(second, "$.vacancyId")).isEqualTo(JsonPath.<String>read(first, "$.vacancyId"));
        assertThat(vacancyCount()).isEqualTo(1);
        mvc.perform(get("/api/vacancies/" + JsonPath.<String>read(first, "$.vacancyId")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Example Role Alpha"))
                .andExpect(jsonPath("$.company").value("Northwind Labs"))
                .andExpect(jsonPath("$.location").value("Prague, Czechia"))
                .andExpect(jsonPath("$.status").value("New"))
                .andExpect(jsonPath("$.url").value(ALPHA));
    }

    @Test
    void aPostingAlreadyInThePipelineIsNotDuplicatedEvenWithTrackingParametersOnTheStoredLink() throws Exception {
        String existing = mvc.perform(post("/api/vacancies").contentType("application/json")
                        .content("{\"title\":\"Alpha elsewhere\",\"company\":\"Northwind Labs\",\"url\":\"" + ALPHA + "?trk=email\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String existingId = JsonPath.read(existing, "$.id");

        mvc.perform(get("/api/mail")).andExpect(jsonPath("$[0].jobs[0].vacancyId").value(existingId))
                .andExpect(jsonPath("$[0].jobs[1].vacancyId").doesNotExist());
        String added = add(0);

        assertThat(JsonPath.<String>read(added, "$.vacancyId")).isEqualTo(existingId);
        assertThat(JsonPath.<Boolean>read(added, "$.created")).isFalse();
        assertThat(vacancyCount()).isEqualTo(1);
    }

    @Test
    void theListShowsTheVacancyIdOnlyForPostingsThatExist() throws Exception {
        mvc.perform(get("/api/mail")).andExpect(jsonPath("$[0].jobs[0].vacancyId").doesNotExist());

        String vacancyId = JsonPath.read(add(1), "$.vacancyId");

        mvc.perform(get("/api/mail"))
                .andExpect(jsonPath("$[0].jobs[0].vacancyId").doesNotExist())
                .andExpect(jsonPath("$[0].jobs[1].vacancyId").value(vacancyId));
    }

    @Test
    void anUnknownMailOrPostingNumberIsNotFound() throws Exception {
        mvc.perform(post("/api/mail/" + UUID.randomUUID() + "/jobs/0/add")).andExpect(status().isNotFound());
        mvc.perform(post("/api/mail/" + mailId + "/jobs/2/add")).andExpect(status().isNotFound());
        mvc.perform(post("/api/mail/" + mailId + "/jobs/-1/add")).andExpect(status().isNotFound());
        assertThat(vacancyCount()).isZero();
    }

    @Test
    void aPostingWithAnUnsafeLinkIsRefused() throws Exception {
        jdbc.update("UPDATE correspondence SET jobs = jsonb_set(jobs, '{0,url}', '\"javascript:alert(1)\"') WHERE id = ?::uuid", mailId);

        mvc.perform(post("/api/mail/" + mailId + "/jobs/0/add")).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy WHERE url LIKE 'javascript:%'", Integer.class)).isZero();
    }
}
