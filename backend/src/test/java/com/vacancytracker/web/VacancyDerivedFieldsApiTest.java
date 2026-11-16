package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The best profile score and the sources of a vacancy are part of every vacancy answer (columns of the Pipeline table). */
@SpringBootTest
@AutoConfigureMockMvc
class VacancyDerivedFieldsApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID add(String title, Integer score) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO vacancy (id, title, company, status, match_score, first_seen_at, last_seen_at)"
                        + " VALUES (?, ?, 'Acme', 'New', ?, ?, ?)",
                id, title, score, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return id;
    }

    private void source(UUID vacancy, String adapter, Instant firstSeen) {
        jdbc.update("INSERT INTO vacancy_source (id, vacancy_id, adapter, external_id, first_seen_at, last_seen_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), vacancy, adapter, UUID.randomUUID().toString(), Timestamp.from(firstSeen), Timestamp.from(firstSeen));
    }

    @Test
    void theListCarriesScoreAndSourcesAndLeavesThemEmptyWhenUnknown() throws Exception {
        UUID scored = add("Scored", 87);
        add("Unscored", null);
        source(scored, "remotive", Instant.parse("2026-11-02T10:00:00Z"));
        source(scored, "greenhouse", Instant.parse("2026-11-01T10:00:00Z"));

        mvc.perform(get("/api/vacancies").param("sort", "title:asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Scored"))
                .andExpect(jsonPath("$.items[0].matchScore").value(87))
                .andExpect(jsonPath("$.items[0].sources[0]").value("greenhouse"))
                .andExpect(jsonPath("$.items[0].sources[1]").value("remotive"))
                .andExpect(jsonPath("$.items[1].matchScore").doesNotExist())
                .andExpect(jsonPath("$.items[1].sources").isEmpty());
    }

    @Test
    void oneVacancyCarriesThemToo() throws Exception {
        UUID id = add("One", 40);
        source(id, "lever", Instant.parse("2026-11-01T10:00:00Z"));

        mvc.perform(get("/api/vacancies/" + id))
                .andExpect(jsonPath("$.matchScore").value(40))
                .andExpect(jsonPath("$.sources[0]").value("lever"));
        mvc.perform(post("/api/vacancies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Saved\"}"))
                .andExpect(jsonPath("$.matchScore").value(40))
                .andExpect(jsonPath("$.sources[0]").value("lever"));
    }

    @Test
    void aVacancyAddedByHandHasNoSourcesAndNoScore() throws Exception {
        mvc.perform(post("/api/vacancies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"By hand\",\"company\":\"Acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.matchScore").doesNotExist());
    }

    @Test
    void anEmptyListStillAnswers() throws Exception {
        mvc.perform(get("/api/vacancies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
