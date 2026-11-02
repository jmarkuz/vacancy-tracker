package com.vacancytracker.collection.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryAndRateLimitTest {

    private static final Clock FIXED = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-11-01T10:00:00Z");
        }
    };

    @Test
    void backoffGrowsAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(6, 500, 4_000, new Random(1));

        assertThat(policy.delayMillis(1, null)).isBetween(500L, 749L);
        assertThat(policy.delayMillis(2, null)).isBetween(1000L, 1249L);
        assertThat(policy.delayMillis(10, null)).isBetween(4000L, 4249L);
    }

    @Test
    void retryAfterOverridesBackoffAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(4, 500, 30_000, new Random(1));

        assertThat(policy.delayMillis(1, Duration.ofSeconds(3))).isEqualTo(3000L);
        assertThat(policy.delayMillis(1, Duration.ofHours(1))).isEqualTo(60_000L);
    }

    @Test
    void sameHostIsSpacedAndOtherHostsAreNot() {
        List<Long> sleeps = new ArrayList<>();
        DomainRateLimiter limiter = new DomainRateLimiter(1000, FIXED, sleeps::add);

        limiter.acquire("a.example");
        limiter.acquire("a.example");
        limiter.acquire("b.example");

        assertThat(sleeps).containsExactly(1000L);
    }
}
