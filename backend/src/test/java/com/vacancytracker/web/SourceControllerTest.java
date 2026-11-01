package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.collection.health.SourceHealth;
import com.vacancytracker.collection.pipeline.RunCoordinator;
import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SourceControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SourceHealth health;

    @Autowired
    private JdbcTemplate template;

    @Autowired
    private RunCoordinator coordinator;

    @Test
    void listsEveryAdapterWithHealth() throws Exception {
        mvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'ats-greenhouse')].health").value(org.hamcrest.Matchers.hasItem("unknown")));
    }

    @Test
    void repeatedFailuresShowWarnThenErrorWithText() throws Exception {
        template.execute("TRUNCATE source_state CASCADE");
        for (int i = 0; i < 2; i++) {
            health.recordFailure("arbeitnow", "HTTP 503");
        }
        mvc.perform(get("/api/sources"))
                .andExpect(jsonPath("$[?(@.id == 'arbeitnow')].health").value(org.hamcrest.Matchers.hasItem("warn")))
                .andExpect(jsonPath("$[?(@.id == 'arbeitnow')].lastError").value(org.hamcrest.Matchers.hasItem("HTTP 503")));

        for (int i = 0; i < 3; i++) {
            health.recordFailure("arbeitnow", "HTTP 503");
        }
        mvc.perform(get("/api/sources"))
                .andExpect(jsonPath("$[?(@.id == 'arbeitnow')].health").value(org.hamcrest.Matchers.hasItem("error")));
    }

    @Test
    void refreshOfOneSourceStoresTheScope() throws Exception {
        // Earlier tests may still have a run in progress; a trigger would then join it instead.
        long deadline = System.currentTimeMillis() + 10_000;
        while (coordinator.activeRun().isPresent() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        String body = mvc.perform(post("/api/sources/ats-greenhouse/refresh"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");

        assertThat(template.queryForObject("SELECT scope FROM run WHERE id = ?::uuid", String.class, runId))
                .isEqualTo("ats-greenhouse");
    }

    @Test
    void unknownSourceIsNotFound() throws Exception {
        mvc.perform(post("/api/sources/does-not-exist/refresh")).andExpect(status().isNotFound());
    }
}
