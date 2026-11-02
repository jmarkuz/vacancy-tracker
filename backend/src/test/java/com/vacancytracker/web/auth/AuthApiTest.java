package com.vacancytracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.access-token=correct-horse-battery-staple", "app.auth.max-failures=3"})
@AutoConfigureMockMvc
@ActiveProfiles("vps")
class AuthApiTest extends AbstractIntegrationTest {

    private static final String TOKEN = "correct-horse-battery-staple";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FailureLimiter limiter;

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String login(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    @Test
    void apiRequestsWithoutATokenGet401AndTheOpenPartsStayOpen() throws Exception {
        mvc.perform(get("/api/vacancies").with(from("10.1.0.1")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/api/runs").with(from("10.1.0.1"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profiles").with(from("10.1.0.1"))).andExpect(status().isUnauthorized());

        mvc.perform(get("/actuator/health").with(from("10.1.0.1"))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/status").with(from("10.1.0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void pathTricksDoNotSkipTheTokenCheck() throws Exception {
        mvc.perform(get("/api;a=b/vacancies").with(from("10.1.0.9"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api;/vacancies").with(from("10.1.0.9"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vacancies;x=1").with(from("10.1.0.9"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aBearerTokenOpensTheApi() throws Exception {
        mvc.perform(get("/api/vacancies").header("Authorization", "Bearer " + TOKEN).with(from("10.1.0.2")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vacancies").header("Authorization", "bearer " + TOKEN).with(from("10.1.0.2")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vacancies").header("Authorization", "Bearer wrong").with(from("10.1.0.2")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vacancies").header("Authorization", "Basic " + TOKEN).with(from("10.1.0.2")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theTokenInAQueryStringIsNotAccepted() throws Exception {
        mvc.perform(get("/api/vacancies?token=" + TOKEN).with(from("10.1.0.3"))).andExpect(status().isUnauthorized());
    }

    @Test
    void loginSetsAnHttpOnlySameSiteCookieThatThenWorks() throws Exception {
        String setCookie = mvc.perform(post("/api/auth/login").contentType(JSON).content(login(TOKEN)).with(from("10.1.0.4")))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(setCookie).contains("vt_token=" + TOKEN).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/")
                .contains("Max-Age=").doesNotContain("Secure");
        mvc.perform(get("/api/vacancies").cookie(new Cookie("vt_token", TOKEN)).with(from("10.1.0.4"))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/status").cookie(new Cookie("vt_token", TOKEN)).with(from("10.1.0.4")))
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/vacancies").cookie(new Cookie("vt_token", "forged")).with(from("10.1.0.4")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theCookieIsSecureWhenTheRequestCameOverHttps() throws Exception {
        String setCookie = mvc.perform(post("/api/auth/login").contentType(JSON).content(login(TOKEN))
                        .header("X-Forwarded-Proto", "https").with(from("10.1.0.5")))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(setCookie).contains("Secure");
    }

    @Test
    void aWrongTokenAtLoginIs401AndSetsNoCookie() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(JSON).content(login("nope")).with(from("10.1.0.6")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{}").with(from("10.1.0.6")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedWrongTokensAreRateLimitedEvenForTheCorrectTokenAndOtherClientsAreNotAffected() throws Exception {
        String ip = "10.2.0.1";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").contentType(JSON).content(login("guess" + i)).with(from(ip)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login").contentType(JSON).content(login(TOKEN)).with(from(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/vacancies").header("Authorization", "Bearer " + TOKEN).with(from(ip)))
                .andExpect(status().isTooManyRequests());

        mvc.perform(get("/api/vacancies").header("Authorization", "Bearer " + TOKEN).with(from("10.2.0.2")))
                .andExpect(status().isOk());
    }

    @Test
    void wrongBearerTokensOnTheApiCountToo() throws Exception {
        String ip = "10.2.0.3";
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/vacancies").header("Authorization", "Bearer guess" + i).with(from(ip)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/api/vacancies").header("Authorization", "Bearer guess").with(from(ip)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void requestsWithNoTokenAtAllDoNotLockAClientOut() throws Exception {
        String ip = "10.2.0.4";
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/vacancies").with(from(ip))).andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/api/vacancies").header("Authorization", "Bearer " + TOKEN).with(from(ip))).andExpect(status().isOk());
    }

    @Test
    void aSuccessfulLoginClearsEarlierFailures() throws Exception {
        String ip = "10.2.0.5";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login").contentType(JSON).content(login("bad")).with(from(ip)));
        }
        mvc.perform(post("/api/auth/login").contentType(JSON).content(login(TOKEN)).with(from(ip))).andExpect(status().isNoContent());

        assertThat(limiter.retryAfter(ip)).isZero();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login").contentType(JSON).content(login("bad")).with(from(ip)));
        }
        mvc.perform(post("/api/auth/login").contentType(JSON).content(login(TOKEN)).with(from(ip))).andExpect(status().isNoContent());
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        String setCookie = mvc.perform(post("/api/auth/logout").with(from("10.1.0.7")))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(setCookie).contains("vt_token=;").contains("Max-Age=0");
    }

    @Test
    void theSinglePageAppItselfIsServedWithoutAToken() throws Exception {
        mvc.perform(get("/index.html").with(from("10.1.0.8"))).andExpect(status().is2xxSuccessful());
    }
}
