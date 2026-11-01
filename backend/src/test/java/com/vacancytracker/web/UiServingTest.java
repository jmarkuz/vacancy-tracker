package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

/** The UI bundle is built by the frontend (npm run build) and copied into static/. Skipped if absent. */
@SpringBootTest
@AutoConfigureMockMvc
class UiServingTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void indexPageIsServed() throws Exception {
        Assumptions.assumeTrue(new ClassPathResource("static/index.html").exists(),
                "UI bundle not built; run npm run build in frontend/");
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"app\">")));
    }
}
