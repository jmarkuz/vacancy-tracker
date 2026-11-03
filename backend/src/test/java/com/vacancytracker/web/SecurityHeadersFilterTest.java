package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Every response carries the browser security headers, whatever its status. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersFilterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static void expectHeaders(ResultActions result) throws Exception {
        result.andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    void anApiAnswerCarriesTheHeaders() throws Exception {
        expectHeaders(mvc.perform(get("/api/vacancies")).andExpect(status().isOk()));
    }

    @Test
    void aMissingPageCarriesTheHeaders() throws Exception {
        expectHeaders(mvc.perform(get("/no-such-page")));
    }

    @Test
    void aRequestRefusedByTheHostCheckCarriesThemToo() throws Exception {
        expectHeaders(mvc.perform(get("/api/vacancies").header("Host", "evil.example")).andExpect(status().isForbidden()));
    }

    @Test
    void thePolicyAllowsNoInlineScriptNoEvalNoPluginsAndNoFraming() {
        String csp = SecurityHeadersFilter.CSP;

        assertThat(csp).contains("default-src 'self'", "script-src 'self'", "object-src 'none'", "frame-ancestors 'none'",
                "base-uri 'self'", "form-action 'self'");
        assertThat(csp).doesNotContain("unsafe-eval");
        // 'unsafe-inline' appears once, for style attributes only.
        assertThat(csp.split("unsafe-inline", -1)).hasSize(2);
        assertThat(csp).contains("style-src-attr 'unsafe-inline'");
        assertThat(csp).doesNotContain("*");
    }
}
