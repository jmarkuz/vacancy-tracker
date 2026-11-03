package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Tying a recruiter's mail to an application by hand, and keeping later mail from the same person with it. */
@SpringBootTest
@AutoConfigureMockMvc
class MailLinkApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private String vacancy(String title, String company) throws Exception {
        String created = mvc.perform(post("/api/vacancies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"company\":\"" + company + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/vacancies/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Applied\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private String importMail(String from, String id, String subject) throws Exception {
        String eml = "From: Jane <" + from + ">\r\nTo: owner@test.example\r\nSubject: " + subject + "\r\n"
                + "Date: Tue, 03 Nov 2026 10:00:00 +0000\r\nMessage-ID: <" + id + "@x.example>\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nHello, I am a recruiter and we have a job position for your resume and CV.\r\n";
        String outcome = mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(outcome, "$.stored")).isTrue();
        return JsonPath.read(outcome, "$.correspondence.id");
    }

    private String mail(String id) throws Exception {
        return mvc.perform(get("/api/mail")).andReturn().getResponse().getContentAsString()
                .transform(body -> JsonPath.<java.util.List<java.util.Map<String, Object>>>read(body, "$[?(@.id == '" + id + "')]").get(0).toString());
    }

    private String vacancyOf(String id) throws Exception {
        String body = mvc.perform(get("/api/mail")).andReturn().getResponse().getContentAsString();
        Object v = JsonPath.<java.util.List<Object>>read(body, "$[?(@.id == '" + id + "')].vacancyId").get(0);
        return v == null ? null : v.toString();
    }

    private void link(String mailId, String vacancyId) throws Exception {
        mvc.perform(put("/api/mail/" + mailId + "/vacancy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancyId\":" + (vacancyId == null ? "null" : "\"" + vacancyId + "\"") + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void aMailLinkedByHandPullsEarlierAndLaterMailFromThatSenderToTheApplication() throws Exception {
        String application = vacancy("Backend Engineer", "Zeta Corp");
        String earlier = importMail("jane@recruiting.example", "m1", "Hello from a recruiter");
        String first = importMail("jane@recruiting.example", "m2", "Quick question");
        String other = importMail("bob@elsewhere.example", "m3", "Another role");
        assertThat(vacancyOf(first)).isNull();

        link(first, application);

        assertThat(vacancyOf(first)).isEqualTo(application);
        assertThat(vacancyOf(earlier)).isEqualTo(application);
        assertThat(vacancyOf(other)).isNull();
        String later = importMail("jane@recruiting.example", "m4", "Following up");
        assertThat(vacancyOf(later)).isEqualTo(application);
        assertThat(mail(later)).contains("Same sender as a mail you linked.");
        assertThat(vacancyOf(importMail("bob@elsewhere.example", "m5", "Still me"))).isNull();
    }

    @Test
    void aJobBoardsNoReplyAddressIsNotTreatedAsOnePerson() throws Exception {
        String application = vacancy("Backend Engineer", "Zeta Corp");
        String first = importMail("jobs-noreply@linkedin.com", "n1", "Saved job reminder");
        link(first, application);

        assertThat(vacancyOf(importMail("jobs-noreply@linkedin.com", "n2", "Another reminder"))).isNull();
    }

    @Test
    void theApplicationsToChooseFromAreTheOnesPastTheInbox() throws Exception {
        String applied = vacancy("Backend Engineer", "Zeta Corp");
        mvc.perform(post("/api/vacancies").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Untouched\",\"company\":\"Nope\"}"))
                .andExpect(status().isCreated());

        String body = mvc.perform(get("/api/mail/applications")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<java.util.List<String>>read(body, "$[*].id")).contains(applied);
        assertThat(JsonPath.<java.util.List<String>>read(body, "$[*].title")).doesNotContain("Untouched");
    }

    @Test
    void aMailCanBeUnlinkedAgain() throws Exception {
        String application = vacancy("Backend Engineer", "Zeta Corp");
        String id = importMail("jane@recruiting.example", "u1", "Hello");
        link(id, application);
        link(id, null);

        assertThat(vacancyOf(id)).isNull();
    }

    @Test
    void theSameMailImportedTwiceIsStoredOnce() throws Exception {
        String first = importMail("jane@recruiting.example", "dup", "Hello");
        String eml = "From: Jane <jane@recruiting.example>\r\nTo: owner@test.example\r\nSubject: Hello\r\n"
                + "Date: Tue, 03 Nov 2026 10:00:00 +0000\r\nMessage-ID: <dup@x.example>\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nHello, I am a recruiter and we have a job position for your resume and CV.\r\n";

        String again = mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Boolean>read(again, "$.stored")).isFalse();
        assertThat(JsonPath.<String>read(again, "$.reason")).isEqualTo("already stored");
        assertThat(JsonPath.<String>read(again, "$.correspondence.id")).isEqualTo(first);
        String all = mvc.perform(get("/api/mail")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<Object>>read(all, "$[?(@.mailKey == 'reply:<dup@x.example>')]")).hasSize(1);
    }
}
