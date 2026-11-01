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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void triggerReturnsARunThatCanBeReadBack() throws Exception {
        String body = mvc.perform(post("/api/runs"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.runId");

        mvc.perform(get("/api/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(id))
                .andExpect(jsonPath("$.status").isNotEmpty());
    }

    @Test
    void unknownRunIsNotFound() throws Exception {
        mvc.perform(get("/api/runs/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}
