package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OriginFilterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void aCrossSiteWriteIsRefusedAndASameSiteOrOriginlessOneIsNot() throws Exception {
        mvc.perform(post("/api/profiles/import").header("Host", "localhost:8080").header("Origin", "https://evil.example")
                .contentType("text/plain").content("x")).andExpect(status().isForbidden());
        mvc.perform(post("/api/profiles/validate").header("Host", "localhost:8080").header("Origin", "http://localhost:8080")
                .contentType("application/json").content("{}")).andExpect(status().isOk());
        mvc.perform(get("/api/vacancies").header("Host", "localhost:8080").header("Origin", "https://evil.example"))
                .andExpect(status().isOk());
    }

    @Test
    void hostsAreComparedWithTheirPort() {
        assertThat(OriginFilter.sameHost("http://localhost:8080", "localhost:8080")).isTrue();
        assertThat(OriginFilter.sameHost("https://app.example.com", "app.example.com")).isTrue();
        assertThat(OriginFilter.sameHost("http://localhost:3000", "localhost:8080")).isFalse();
        assertThat(OriginFilter.sameHost("null", "localhost:8080")).isFalse();
        assertThat(OriginFilter.sameHost(null, "localhost")).isFalse();
    }
}
