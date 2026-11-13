package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Browser caching of the UI bundle, and the gzip setting that shrinks it. */
@SpringBootTest
@AutoConfigureMockMvc
class StaticCacheConfigTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Value("${server.compression.enabled}")
    private boolean compression;

    @Value("${server.compression.mime-types}")
    private String mimeTypes;

    @Test
    void aHashedBundleFileIsCachedForAYearAndNeverRechecked() throws Exception {
        mvc.perform(get("/assets/cache-test.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void thePageThatNamesTheBundleIsRevalidatedOnEveryVisit() throws Exception {
        mvc.perform(get("/cache-test.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void aMissingBundleFileIsNotRemembered() throws Exception {
        mvc.perform(get("/assets/gone-abc123.js"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"));
    }

    @Test
    void theApiIsNotGivenAStaticCachePolicy() throws Exception {
        mvc.perform(get("/api/vacancies")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("immutable"))));
    }

    @Test
    void gzipIsOnForTheBundleAndForJson() {
        assertThat(compression).isTrue();
        assertThat(mimeTypes).contains("text/css", "application/javascript", "text/javascript", "application/json");
    }
}
