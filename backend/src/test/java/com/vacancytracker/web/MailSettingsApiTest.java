package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** How often the mailbox is read: default, saving, rejecting other values, and persistence in the database. */
@SpringBootTest
@AutoConfigureMockMvc
class MailSettingsApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM app_setting");
    }

    private void putBody(String body, int expected) throws Exception {
        mvc.perform(put("/api/mail/settings").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected));
    }

    @Test
    void withNothingStoredThePropertyDefaultApplies() throws Exception {
        mvc.perform(get("/api/mail/settings")).andExpect(status().isOk()).andExpect(jsonPath("$.intervalMinutes").value(15));
        mvc.perform(get("/api/mail/status")).andExpect(status().isOk()).andExpect(jsonPath("$.intervalMinutes").value(15))
                .andExpect(jsonPath("$.nextCheck").doesNotExist());
    }

    @Test
    void aChosenIntervalIsStoredAndShownInSettingsAndStatus() throws Exception {
        mvc.perform(put("/api/mail/settings").contentType(MediaType.APPLICATION_JSON).content("{\"intervalMinutes\":45}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intervalMinutes").value(45));

        assertThat(jdbc.queryForObject("SELECT value FROM app_setting WHERE key = 'mail.interval.minutes'", String.class)).isEqualTo("45");
        mvc.perform(get("/api/mail/settings")).andExpect(jsonPath("$.intervalMinutes").value(45));
        mvc.perform(get("/api/mail/status")).andExpect(jsonPath("$.intervalMinutes").value(45));

        putBody("{\"intervalMinutes\":5}", 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/mail/settings")).andExpect(jsonPath("$.intervalMinutes").value(5));
    }

    @Test
    void otherValuesAreRejectedWith400AndNothingChanges() throws Exception {
        putBody("{\"intervalMinutes\":30}", 200);
        for (String bad : new String[] {"{\"intervalMinutes\":7}", "{\"intervalMinutes\":0}", "{\"intervalMinutes\":120}",
                "{\"intervalMinutes\":-15}", "{}", "{\"intervalMinutes\":null}"}) {
            putBody(bad, 400);
        }
        mvc.perform(get("/api/mail/settings")).andExpect(jsonPath("$.intervalMinutes").value(30));
    }

    @Test
    void aStoredValueThatIsNotAStepFallsBackToTheDefault() throws Exception {
        jdbc.update("INSERT INTO app_setting (key, value) VALUES ('mail.interval.minutes', 'soon')");
        mvc.perform(get("/api/mail/settings")).andExpect(jsonPath("$.intervalMinutes").value(15));
    }
}
