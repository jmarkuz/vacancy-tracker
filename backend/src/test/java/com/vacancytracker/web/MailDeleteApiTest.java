package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Deleting stored job mail: chosen mails, and everything older than some days. The mailbox is never touched. */
@SpringBootTest
@AutoConfigureMockMvc
class MailDeleteApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE correspondence");
    }

    private String importMail(String id, int daysAgo) throws Exception {
        String date = ZonedDateTime.now(ZoneOffset.UTC).minusDays(daysAgo).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        String eml = "From: Acme HR <hr@acme.example>\r\nTo: owner@test.example\r\nSubject: Application " + id + "\r\n"
                + "Date: " + date + "\r\nMessage-ID: <" + id + "@acme.example>\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "Thank you for your application. Unfortunately we decided to move forward with other candidates.\r\n";
        String outcome = mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(outcome, "$.stored")).isTrue();
        return JsonPath.read(outcome, "$.correspondence.id");
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM correspondence", Integer.class);
    }

    @Test
    void chosenMailsAreDeletedAndTheRestStay() throws Exception {
        String a = importMail("a", 1);
        String b = importMail("b", 2);
        importMail("c", 3);

        mvc.perform(post("/api/mail/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + a + "\",\"" + b + "\",\"" + a + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void anIdThatDoesNotExistDeletesNothing() throws Exception {
        importMail("d", 1);

        mvc.perform(post("/api/mail/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + java.util.UUID.randomUUID() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(0));

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void anEmptyChoiceIsRefused() throws Exception {
        mvc.perform(post("/api/mail/delete").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mailOlderThanTheGivenDaysIsDeleted() throws Exception {
        importMail("new", 2);
        importMail("old1", 20);
        importMail("old2", 40);

        mvc.perform(post("/api/mail/delete-old").contentType(MediaType.APPLICATION_JSON).content("{\"days\":14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));

        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subject FROM correspondence", String.class)).isEqualTo("Application new");
    }

    @Test
    void aNumberOfDaysBelowOneIsRefused() throws Exception {
        importMail("keep", 100);

        mvc.perform(post("/api/mail/delete-old").contentType(MediaType.APPLICATION_JSON).content("{\"days\":0}"))
                .andExpect(status().isBadRequest());

        assertThat(count()).isEqualTo(1);
    }
}
