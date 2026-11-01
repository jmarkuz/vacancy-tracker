package com.vacancytracker.web.auth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Beans that exist only in the vps profile (ADR-017, ADR-033). The local profile has no authentication. */
@Configuration
@Profile("vps")
class AuthConfig {

    @Bean
    AccessToken accessToken(@Value("${app.access-token:}") String token) {
        return new AccessToken(token);
    }

    @Bean
    FailureLimiter failureLimiter(Clock clock,
                                  @Value("${app.auth.max-failures:5}") int maxFailures,
                                  @Value("${app.auth.failure-window-minutes:10}") int windowMinutes) {
        return new FailureLimiter(maxFailures, Duration.ofMinutes(windowMinutes), clock);
    }

    @Bean
    AuthFilter authFilter(AccessToken token, FailureLimiter limiter) {
        return new AuthFilter(token, limiter);
    }
}
