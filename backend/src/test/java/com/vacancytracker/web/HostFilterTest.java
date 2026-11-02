package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HostFilterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void aRebindingPageCannotReadTheApi() throws Exception {
        mvc.perform(get("/api/export/backup.json").header("Host", "evil.example:8080")).andExpect(status().isForbidden());
        mvc.perform(get("/api/vacancies").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/vacancies").header("Host", "localhost")).andExpect(status().isOk());
    }

    @Test
    void loopbackAndConfiguredNamesPassWithOrWithoutAPort() {
        HostFilter filter = new HostFilter(" jobs.example.com ,Other.Example ");

        assertThat(filter.allowed("localhost:8080")).isTrue();
        assertThat(filter.allowed("[::1]:8080")).isTrue();
        assertThat(filter.allowed("JOBS.example.com")).isTrue();
        assertThat(filter.allowed("other.example:443")).isTrue();
        assertThat(filter.allowed("evil.example")).isFalse();
        assertThat(filter.allowed("localhost.evil.example")).isFalse();
        assertThat(filter.allowed("")).isFalse();
        assertThat(filter.allowed(null)).isFalse();
    }
}
