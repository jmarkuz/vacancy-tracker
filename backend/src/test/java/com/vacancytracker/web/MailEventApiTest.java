package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The date of an interview is stored (migration V27) and returned as the mail's event. */
@SpringBootTest
@AutoConfigureMockMvc
class MailEventApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static String eml(String id, String subject, String body) {
        return "From: Jane Recruiter <jane@acme.example>\r\nTo: owner@test.example\r\nSubject: " + subject + "\r\n"
                + "Date: Thu, 05 Nov 2026 09:00:00 +0000\r\nMessage-ID: <" + id + "@acme.example>\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + body + "\r\n";
    }

    private void importMail(String eml, boolean stored) throws Exception {
        mvc.perform(post("/api/mail/import").contentType("message/rfc822").content(eml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(stored));
    }

    @Test
    void theListReturnsTheEventOfAnInterviewMail() throws Exception {
        importMail(eml("ev1", "Interview invitation", "Your interview is on Thursday, 12 November 2026 at 14:30 CET. Zoom link follows."), true);

        mvc.perform(get("/api/mail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event.startsAt").value("2026-11-12T13:30:00Z"))
                .andExpect(jsonPath("$[0].event.zoneKnown").value(true))
                .andExpect(jsonPath("$[0].event.text").value("12 November 2026 at 14:30 CET"));
        assertThat(jdbc.queryForObject("SELECT event_at FROM correspondence", java.sql.Timestamp.class).toLocalDateTime().toString())
                .isEqualTo("2026-11-12T13:30");
    }

    @Test
    void aLocalTimeHasNoZoneInTheAnswer() throws Exception {
        importMail(eml("ev2", "Interview", "Please join the call on 12.11.2026 at 14:30."), true);

        mvc.perform(get("/api/mail"))
                .andExpect(jsonPath("$[0].event.startsAt").value("2026-11-12T14:30"))
                .andExpect(jsonPath("$[0].event.zoneKnown").value(false));
    }

    @Test
    void aMailWithoutADateHasANullEvent() throws Exception {
        importMail(eml("ev3", "Interview", "We would like to invite you to an interview. Please send your times."), true);

        mvc.perform(get("/api/mail")).andExpect(jsonPath("$[0].event").value((Object) null));
    }

    @Test
    void aStoredMailGetsItsEventWhenItIsImportedAgain() throws Exception {
        String mail = eml("ev4", "Interview", "The interview is on 12 November 2026 at 14:30.");
        importMail(mail, true);
        jdbc.execute("UPDATE correspondence SET event_at = NULL, event_zone_known = NULL, event_text = NULL");
        mvc.perform(get("/api/mail")).andExpect(jsonPath("$[0].event").value((Object) null));

        importMail(mail, false);

        mvc.perform(get("/api/mail")).andExpect(jsonPath("$[0].event.startsAt").value("2026-11-12T14:30"));
    }
}
