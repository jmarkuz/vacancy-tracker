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

/** GET /api/dashboard/replies as the Dashboard card reads it. */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardRepliesApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anEmptyDatabaseGivesZerosAndANullMedian() throws Exception {
        mvc.perform(get("/api/dashboard/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.replied").value(0))
                .andExpect(jsonPath("$.medianDays").doesNotExist())
                .andExpect(jsonPath("$.outcomes.interview").value(0));
    }

    @Test
    void anAppliedVacancyWithAReplyIsReported() throws Exception {
        Instant applied = Instant.now().minus(5, ChronoUnit.DAYS);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO vacancy (id, title, company, status, first_seen_at, last_seen_at) VALUES (?, 'Dev', 'Acme', 'Interview', ?, ?)",
                id, Timestamp.from(applied), Timestamp.from(applied));
        jdbc.update("INSERT INTO status_event (id, vacancy_id, to_status, at) VALUES (?, ?, 'Applied', ?)",
                UUID.randomUUID(), id, Timestamp.from(applied));
        jdbc.update("INSERT INTO correspondence (id, mail_key, received_at, category, vacancy_id, suggestion_state, created_at)"
                        + " VALUES (?, 'k1', ?, 'interview', ?, 'none', now())",
                UUID.randomUUID(), Timestamp.from(applied.plus(2, ChronoUnit.DAYS)), id);

        mvc.perform(get("/api/dashboard/replies?days=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.replied").value(1))
                .andExpect(jsonPath("$.medianDays").value(2.0))
                .andExpect(jsonPath("$.outcomes.interview").value(1))
                .andExpect(jsonPath("$.outcomes.rejection").value(0));
    }

    @Test
    void daysOutOfRangeIsABadRequest() throws Exception {
        mvc.perform(get("/api/dashboard/replies?days=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard/replies?days=1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard/replies?days=abc")).andExpect(status().isBadRequest());
    }
}
