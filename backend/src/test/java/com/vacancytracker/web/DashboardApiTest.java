package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The Dashboard answer as the screen reads it, including the applications that have gone quiet. */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anEmptyDatabaseHasAnEmptyFollowUpList() throws Exception {
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUps").isArray())
                .andExpect(jsonPath("$.followUps").isEmpty());
    }

    @Test
    void aQuietApplicationIsReportedWithItsDaysAndStatus() throws Exception {
        Instant weeksAgo = Instant.now().minus(21, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO vacancy (id, title, company, status, first_seen_at, last_seen_at) VALUES (?, 'Backend Engineer', 'Zeta Corp', 'Applied', ?, ?)",
                UUID.randomUUID(), Timestamp.from(weeksAgo), Timestamp.from(weeksAgo));

        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUps[0].title").value("Backend Engineer"))
                .andExpect(jsonPath("$.followUps[0].company").value("Zeta Corp"))
                .andExpect(jsonPath("$.followUps[0].status").value("Applied"))
                .andExpect(jsonPath("$.followUps[0].quietDays").value(21))
                .andExpect(jsonPath("$.followUps[0].lastActivityAt").exists());
    }
}
