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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** A LinkedIn digest is stored with its postings, and the Mail list returns them as a list of vacancies. */
@SpringBootTest
@AutoConfigureMockMvc
class MailDigestApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static String eml(String from, String id, String html) {
        return "From: LinkedIn <" + from + ">\r\nTo: owner@test.example\r\nSubject: Senior Java roles for you\r\n"
                + "Date: Thu, 05 Nov 2026 09:00:00 +0000\r\nMessage-ID: <" + id + "@linkedin.com>\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n"
                + "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nAbout the position and your resume\r\n"
                + "--b1\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" + html + "\r\n--b1--\r\n";
    }

    private String importMail(String eml) throws Exception {
        String outcome = mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(outcome, "$.stored")).isTrue();
        return outcome;
    }

    @Test
    void theListReturnsThePostingsOfADigestInOrder() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/mail/linkedin-alert.html"), StandardCharsets.UTF_8);

        String outcome = importMail(eml("jobs-noreply@linkedin.com", "digest1", html));

        assertThat(JsonPath.<Integer>read(outcome, "$.correspondence.jobs.length()")).isEqualTo(2);
        mvc.perform(get("/api/mail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobs.length()").value(2))
                .andExpect(jsonPath("$[0].jobs[0].title").value("Example Role Alpha"))
                .andExpect(jsonPath("$[0].jobs[0].company").value("Northwind Labs"))
                .andExpect(jsonPath("$[0].jobs[0].location").value("Prague, Czechia"))
                .andExpect(jsonPath("$[0].jobs[0].url").value("https://www.linkedin.com/jobs/view/3912345678/"))
                .andExpect(jsonPath("$[0].jobs[1].company").value("Vantage Lane"));
    }

    @Test
    void anOrdinaryMailHasAnEmptyList() throws Exception {
        importMail(eml("hr@acme.example", "plain1", "<p>Hello</p>"));

        mvc.perform(get("/api/mail")).andExpect(status().isOk()).andExpect(jsonPath("$[0].jobs.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM correspondence WHERE jobs IS NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void aDigestStoredWithoutPostingsGetsThemWhenItIsImportedAgain() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/mail/linkedin-alert.html"), StandardCharsets.UTF_8);
        String mail = eml("jobs-noreply@linkedin.com", "digest2", html);
        importMail(mail);
        jdbc.execute("UPDATE correspondence SET jobs = NULL");

        mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(mail.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(false));

        mvc.perform(get("/api/mail")).andExpect(jsonPath("$[0].jobs.length()").value(2));
    }
}
