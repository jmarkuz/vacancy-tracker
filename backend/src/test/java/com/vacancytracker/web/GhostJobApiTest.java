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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The "likely ghost" advice as the screens read it. */
@SpringBootTest
@AutoConfigureMockMvc
class GhostJobApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String vacancy(String title) throws Exception {
        String body = mvc.perform(post("/api/vacancies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"company\":\"Zeta Corp\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void aFreshVacancyWithoutASalaryHasOneSignAndIsNotLikely() throws Exception {
        String id = vacancy("Fresh role");

        mvc.perform(get("/api/vacancies/" + id))
                .andExpect(jsonPath("$.ghost.likely").value(false))
                .andExpect(jsonPath("$.ghost.signs").value(1))
                .andExpect(jsonPath("$.ghost.reasons[0]").value("No salary stated"));
    }

    @Test
    void anOldUnsalariedVacancyIsLikelyWithItsReasons() throws Exception {
        String id = vacancy("Old role");
        jdbc.update("UPDATE vacancy SET posted_at = now() - interval '50 days' WHERE id = ?::uuid", id);

        mvc.perform(get("/api/vacancies/" + id))
                .andExpect(jsonPath("$.ghost.likely").value(true))
                .andExpect(jsonPath("$.ghost.signs").value(2))
                .andExpect(jsonPath("$.ghost.reasons[0]").value("Posted 50 days ago"))
                .andExpect(jsonPath("$.ghost.reasons[1]").value("No salary stated"));
    }

    @Test
    void theListCarriesTheAdviceToo() throws Exception {
        String id = vacancy("Listed role");
        jdbc.update("UPDATE vacancy SET posted_at = now() - interval '40 days' WHERE id = ?::uuid", id);

        mvc.perform(get("/api/vacancies"))
                .andExpect(jsonPath("$.items[0].ghost.likely").value(true));
    }
}
