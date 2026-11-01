package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.matching.Criterion;
import com.vacancytracker.matching.MatchService;
import com.vacancytracker.matching.ProfileInput;
import com.vacancytracker.matching.SearchProfileService;
import com.vacancytracker.matching.Thresholds;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class VacancyMatchesApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SearchProfileService profiles;

    @Autowired
    private MatchService matchService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE search_profile CASCADE");
    }

    @Test
    void returnsScoreAndReasonsOfEachProfile() throws Exception {
        profiles.create(new ProfileInput("Main", true, List.of("all"), null, Thresholds.defaults(), List.of(
                new Criterion("kw", "title", "contains_any", mapper.readTree("[\"java\"]"), "prefer", 8, null, "Keyword in title"))));
        String body = mvc.perform(post("/api/vacancies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Java Developer\",\"company\":\"Acme\"}")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        matchService.match(UUID.fromString(id));

        mvc.perform(get("/api/vacancies/" + id + "/matches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].score").value(100))
                .andExpect(jsonPath("$[0].hidden").value(false))
                .andExpect(jsonPath("$[0].reasons[0].criterionId").value("kw"))
                .andExpect(jsonPath("$[0].reasons[0].text").value("+8: Keyword in title"));
    }

    @Test
    void unknownVacancyIsNotFound() throws Exception {
        mvc.perform(get("/api/vacancies/00000000-0000-0000-0000-000000000000/matches")).andExpect(status().isNotFound());
    }
}
