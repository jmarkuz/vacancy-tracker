package com.vacancytracker.collection.http;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Spaces requests to the same host by at least minIntervalMillis (ADR-018). */
public class DomainRateLimiter {

    private final long minIntervalMillis;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Long> nextAllowed = new HashMap<>();

    public DomainRateLimiter(long minIntervalMillis, Clock clock, Sleeper sleeper) {
        this.minIntervalMillis = minIntervalMillis;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public synchronized void acquire(String host) {
        long now = clock.millis();
        long slot = Math.max(now, nextAllowed.getOrDefault(host, now));
        if (slot > now) {
            sleeper.sleep(slot - now);
        }
        nextAllowed.put(host, slot + minIntervalMillis);
    }
}
