package com.vacancytracker.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The default (local) profile needs no token and says so. */
@SpringBootTest
@AutoConfigureMockMvc
class LocalProfileAuthTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void noTokenIsNeededAndStatusSaysSo() throws Exception {
        mvc.perform(get("/api/vacancies")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/status"))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
                .andExpect(status().isNoContent());
    }
}
