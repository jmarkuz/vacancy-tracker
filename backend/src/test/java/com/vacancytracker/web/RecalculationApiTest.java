package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Runs with automatic recalculation on, to check that saving a profile starts a job (docs section 8). */
@SpringBootTest(properties = "app.matching.recalculate-on-save=true")
@AutoConfigureMockMvc
class RecalculationApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void theButtonStartsAJobAndProgressCanBeRead() throws Exception {
        mvc.perform(post("/api/recalculation"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.jobId").isNotEmpty());

        mvc.perform(get("/api/recalculation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").isNotEmpty())
                .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    void savingAProfileStartsARecalculation() throws Exception {
        String profile = "{\"name\":\"Auto " + System.nanoTime() + "\",\"enabled\":true,\"sources\":[\"all\"],"
                + "\"thresholds\":{\"inboxMinScore\":40,\"notifyMinScore\":70,\"maxAgeDays\":60},\"criteria\":[]}";
        String before = mvc.perform(post("/api/recalculation")).andReturn().getResponse().getContentAsString();
        waitUntilFinished();

        mvc.perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isCreated());

        String after = mvc.perform(get("/api/recalculation")).andReturn().getResponse().getContentAsString();
        String jobBefore = JsonPath.read(before, "$.jobId");
        String jobAfter = JsonPath.read(after, "$.jobId");
        org.assertj.core.api.Assertions.assertThat(jobAfter).isNotEqualTo(jobBefore);
    }

    private void waitUntilFinished() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/recalculation")).andReturn().getResponse().getContentAsString();
            if ("COMPLETED".equals(JsonPath.read(body, "$.state"))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The job did not finish");
    }
}
