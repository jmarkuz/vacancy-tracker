package com.vacancytracker.collection.http;

import java.time.Duration;
import java.util.Random;

/** Exponential backoff with jitter; Retry-After wins when the source sends it (ADR-018). */
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseMillis;
    private final long capMillis;
    private final Random random;

    public RetryPolicy(int maxAttempts, long baseMillis, long capMillis, Random random) {
        this.maxAttempts = maxAttempts;
        this.baseMillis = baseMillis;
        this.capMillis = capMillis;
        this.random = random;
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(4, 500, 30_000, new Random());
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Delay before the next attempt; attempt is 1 for the first failure. */
    public long delayMillis(int attempt, Duration retryAfter) {
        if (retryAfter != null) {
            return Math.min(retryAfter.toMillis(), 60_000L);
        }
        long exponential = Math.min(capMillis, baseMillis * (1L << Math.min(attempt - 1, 20)));
        return exponential + random.nextLong(Math.max(1, baseMillis / 2));
    }
}
