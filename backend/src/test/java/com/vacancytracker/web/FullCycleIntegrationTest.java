package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole application cycle through the API, with fake data, the fake AI provider and a test mail delivered to a
 * local IMAP server (GreenMail): vacancy, fit to the resume, tailored documents, package, Applied, the employer's
 * answer, and the suggested status the owner accepts. Nothing leaves the machine.
 */
@SpringBootTest(properties = {
    "app.ai.provider=fake",
    "app.mail.imap.enabled=true",
    "app.mail.imap.host=127.0.0.1",
    "app.mail.imap.port=3143",
    "app.mail.imap.ssl=false",
    "app.mail.imap.username=owner@test.example",
    "app.mail.imap.password=test-imap-password",
    "app.mail.replies.folders=INBOX",
    "app.mail.replies.initial-delay=PT1H"})
@AutoConfigureMockMvc
class FullCycleIntegrationTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String OWNER = "owner@test.example";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(OWNER, OWNER, "test-imap-password"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE ai_consent, correspondence");
    }

    private String json(String method, String url, String body) throws Exception {
        var request = "POST".equals(method) ? post(url) : get(url);
        if (body != null) {
            request = request.contentType(JSON).content(body);
        }
        return mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private void deliver(String fromName, String from, String subject, String text) throws MessagingException, UnsupportedEncodingException {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress(from, fromName, "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, OWNER);
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.saveChanges();
        MAIL.getManagers().getUserManager().getUser(OWNER).deliver(m);
    }

    @Test
    void vacancyFitDocumentsPackageAppliedAnswerAndSuggestedStatus() throws Exception {
        // 1. A vacancy (fake data).
        String vacancy = JsonPath.read(json("POST", "/api/vacancies", """
                {"title":"Backend Engineer","company":"Acme Pay","url":"https://jobs.acmepay.example/backend-1",
                 "description":"Requirements:\\n- 3+ years with Java, Spring Boot, PostgreSQL\\n- Kafka, Docker and AWS are a plus"}
                """), "$.id");

        // 2. The resume, and the fit with its reasons.
        json("POST", "/api/resume/text", """
                {"name":"Jane","text":"Jane Doe\\n\\nSkills\\nJava, Spring Boot, PostgreSQL, Kafka\\n\\nAcme Bank, 2019 - 2025"}
                """);
        String fit = json("GET", "/api/vacancies/" + vacancy + "/fit", null);
        assertThat(JsonPath.<Integer>read(fit, "$.score")).isGreaterThan(50);
        assertThat(JsonPath.<String>read(fit, "$.reasons[0].text")).startsWith("Matches");
        assertThat(JsonPath.<java.util.List<String>>read(fit, "$.missing")).contains("AWS");

        // 3. Tailored documents with the fake AI, after the owner's opt-in; edited before saving.
        json("POST", "/api/ai/consent", null);
        String resumeDraft = JsonPath.read(json("POST", "/api/vacancies/" + vacancy + "/documents/draft", "{\"kind\":\"resume\"}"), "$.content");
        assertThat(resumeDraft).startsWith("# Tailored resume for Backend Engineer at Acme Pay");
        String resume = JsonPath.read(json("POST", "/api/vacancies/" + vacancy + "/documents",
                "{\"kind\":\"resume\",\"content\":\"" + resumeDraft.replace("\n", "\\n") + "\\nEdited by Jane.\",\"origin\":\"ai\","
                        + "\"provider\":\"fake\",\"model\":\"fake-model\"}"), "$.id");
        String letterDraft = JsonPath.read(json("POST", "/api/vacancies/" + vacancy + "/documents/draft", "{\"kind\":\"cover_letter\"}"), "$.content");
        String letter = JsonPath.read(json("POST", "/api/vacancies/" + vacancy + "/documents",
                "{\"kind\":\"cover_letter\",\"content\":\"" + letterDraft.replace("\n", "\\n") + "\",\"origin\":\"ai\"}"), "$.id");

        // 4. The package, and the owner's "I submitted": Applied.
        String pkg = JsonPath.read(json("POST", "/api/vacancies/" + vacancy + "/packages",
                "{\"resumeDocumentId\":\"" + resume + "\",\"coverLetterDocumentId\":\"" + letter + "\"}"), "$.id");
        assertThat(mvc.perform(get("/api/packages/" + pkg + "/download")).andReturn().getResponse().getContentAsByteArray()).isNotEmpty();
        mvc.perform(post("/api/packages/" + pkg + "/submitted")).andExpect(jsonPath("$.vacancyStatus").value("Applied"));

        // 5. The employer answers; a newsletter arrives too.
        deliver("Acme Pay Recruiting", "talent@acmepay.example", "Interview invitation: Backend Engineer",
                "Hi Jane,\nthank you for applying. We would like to invite you to an interview next week.");
        deliver("Some Shop", "news@shop.example", "Black Friday", "Big discounts on shoes.");
        String sync = json("POST", "/api/mail/sync", null);
        assertThat(JsonPath.<Integer>read(sync, "$.stored")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(sync, "$.suggested")).isEqualTo(1);

        // 6. The mail is linked to the application, with a suggested status the owner accepts.
        String pending = json("GET", "/api/mail?pending=true", null);
        assertThat(JsonPath.<String>read(pending, "$[0].vacancyId")).isEqualTo(vacancy);
        assertThat(JsonPath.<String>read(pending, "$[0].category")).isEqualTo("interview");
        assertThat(JsonPath.<String>read(pending, "$[0].suggestedStatus")).isEqualTo("Interview");
        assertThat(JsonPath.<String>read(pending, "$[0].senderName")).isEqualTo("Acme Pay Recruiting");
        String mailId = JsonPath.read(pending, "$[0].id");
        mvc.perform(get("/api/vacancies/" + vacancy)).andExpect(jsonPath("$.status").value("Applied"));

        json("POST", "/api/mail/" + mailId + "/accept", null);

        mvc.perform(get("/api/vacancies/" + vacancy)).andExpect(jsonPath("$.status").value("Interview"));
        String history = json("GET", "/api/vacancies/" + vacancy + "/history", null);
        assertThat(JsonPath.<java.util.List<String>>read(history, "$[*].to")).containsExactly("New", "Applied", "Screening", "Interview");
        assertThat(JsonPath.<String>read(history, "$[3].note")).isEqualTo("from email: Interview invitation: Backend Engineer");

        // Read once: a second check finds nothing new, and the newsletter was never stored.
        assertThat(JsonPath.<Integer>read(json("POST", "/api/mail/sync", null), "$.stored")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM correspondence", Integer.class)).isEqualTo(1);
    }

    @Test
    void aRejectionImportedAsAnEmlFileIsSuggestedAndCanBeDismissed() throws Exception {
        String vacancy = JsonPath.read(json("POST", "/api/vacancies", "{\"title\":\"QA Engineer\",\"company\":\"Beta Soft\"}"), "$.id");
        mvc.perform(post("/api/vacancies/" + vacancy + "/status").contentType(JSON).content("{\"status\":\"Applied\"}"));
        String eml = "From: Beta Soft HR <hr@betasoft.example>\r\nTo: owner@test.example\r\nSubject: QA Engineer at Beta Soft\r\n"
                + "Message-ID: <rej-1@betasoft.example>\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "Unfortunately we decided to move forward with other candidates.\r\n";

        String outcome = mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Boolean>read(outcome, "$.stored")).isTrue();
        assertThat(JsonPath.<String>read(outcome, "$.correspondence.suggestedStatus")).isEqualTo("Rejected");
        String id = JsonPath.read(outcome, "$.correspondence.id");
        json("POST", "/api/mail/" + id + "/dismiss", null);
        mvc.perform(get("/api/vacancies/" + vacancy)).andExpect(jsonPath("$.status").value("Applied"));
        mvc.perform(post("/api/mail/" + id + "/accept")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.stored").value(false))
                .andExpect(jsonPath("$.reason").value("already stored"));
        mvc.perform(get("/api/mail/status")).andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.pending").value(0));
    }
}
