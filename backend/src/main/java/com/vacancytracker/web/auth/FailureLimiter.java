package com.vacancytracker.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Limits wrong access tokens per client (T-071, ADR-033). After {@code maxFailures} wrong tokens within
 * {@code window} the client is blocked until its oldest failure leaves the window, and while blocked even a
 * correct token is refused, so guessing cannot be paced just under the limit. A correct token clears the
 * client's failures. Kept in memory; a restart clears it. The table is capped so it cannot grow without bound.
 */
public final class FailureLimiter {

    static final int MAX_CLIENTS = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    public FailureLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /** How long the client must wait, or zero when it may try. */
    public synchronized Duration retryAfter(String client) {
        Deque<Instant> recent = recent(client);
        if (recent == null || recent.size() < maxFailures) {
            return Duration.ZERO;
        }
        Duration wait = Duration.between(clock.instant(), recent.peekFirst().plus(window));
        return wait.isNegative() ? Duration.ZERO : wait;
    }

    public synchronized void recordFailure(String client) {
        if (failures.size() >= MAX_CLIENTS && !failures.containsKey(client)) {
            purgeExpired();
            if (failures.size() >= MAX_CLIENTS) {
                // Full of live entries: forget the oldest-started one rather than refuse to count this client.
                String oldest = failures.entrySet().stream()
                        .min(java.util.Comparator.comparing(e -> e.getValue().peekFirst())).map(Map.Entry::getKey).orElse(null);
                failures.remove(oldest);
            }
        }
        Deque<Instant> recent = failures.computeIfAbsent(client, k -> new ArrayDeque<>());
        trim(recent);
        recent.addLast(clock.instant());
    }

    public synchronized void recordSuccess(String client) {
        failures.remove(client);
    }

    private Deque<Instant> recent(String client) {
        Deque<Instant> recent = failures.get(client);
        if (recent != null) {
            trim(recent);
            if (recent.isEmpty()) {
                failures.remove(client);
                return null;
            }
        }
        return recent;
    }

    private void trim(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(window);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.removeFirst();
        }
    }

    private void purgeExpired() {
        for (Iterator<Map.Entry<String, Deque<Instant>>> it = failures.entrySet().iterator(); it.hasNext(); ) {
            Deque<Instant> recent = it.next().getValue();
            trim(recent);
            if (recent.isEmpty()) {
                it.remove();
            }
        }
    }

    synchronized int size() {
        return failures.size();
    }
}
