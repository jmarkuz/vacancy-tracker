package com.vacancytracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FailureLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final FailureLimiter limiter = new FailureLimiter(3, Duration.ofMinutes(10), clock);

    @Test
    void aClientMayTryUntilItReachesTheLimit() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");

        assertThat(limiter.retryAfter("a")).isZero();

        limiter.recordFailure("a");

        assertThat(limiter.retryAfter("a")).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theWaitShrinksAndEndsWhenTheOldestFailureLeavesTheWindow() {
        limiter.recordFailure("a");
        clock.advance(Duration.ofMinutes(4));
        limiter.recordFailure("a");
        limiter.recordFailure("a");

        assertThat(limiter.retryAfter("a")).isEqualTo(Duration.ofMinutes(6));

        clock.advance(Duration.ofMinutes(6));

        assertThat(limiter.retryAfter("a")).isZero();
    }

    @Test
    void clientsAreCountedSeparately() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("a");
        }

        assertThat(limiter.retryAfter("a")).isPositive();
        assertThat(limiter.retryAfter("b")).isZero();
    }

    @Test
    void aCorrectTokenClearsTheFailures() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        limiter.recordSuccess("a");
        limiter.recordFailure("a");
        limiter.recordFailure("a");

        assertThat(limiter.retryAfter("a")).isZero();
    }

    @Test
    void failuresOlderThanTheWindowDoNotCount() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        clock.advance(Duration.ofMinutes(11));
        limiter.recordFailure("a");

        assertThat(limiter.retryAfter("a")).isZero();
    }

    @Test
    void theTableStaysBounded() {
        FailureLimiter small = new FailureLimiter(3, Duration.ofMinutes(10), clock);
        for (int i = 0; i < FailureLimiter.MAX_CLIENTS + 50; i++) {
            small.recordFailure("client-" + i);
            clock.advance(Duration.ofMillis(1));
        }

        assertThat(small.size()).isLessThanOrEqualTo(FailureLimiter.MAX_CLIENTS);
        assertThat(small.retryAfter("client-" + (FailureLimiter.MAX_CLIENTS + 49))).isZero();
    }
}
