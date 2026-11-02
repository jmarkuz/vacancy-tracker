package com.vacancytracker.collection.http;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Limits calls per source within a time window (for example 4 per day). The count is stored, so a restart
 * does not reset the budget.
 */
@Component
public class CallBudget {

    private final JdbcClient jdbc;

    public CallBudget(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes one call from the budget if the window has room. Returns false when the budget is used up. */
    public boolean tryAcquire(String adapter, String windowKey, int maxCalls) {
        if (maxCalls <= 0) {
            return false;
        }
        return jdbc.sql("INSERT INTO source_quota (adapter, window_key, calls) VALUES (:adapter, :window, 1)"
                        + " ON CONFLICT (adapter, window_key) DO UPDATE SET calls = source_quota.calls + 1"
                        + " WHERE source_quota.calls < :max RETURNING calls")
                .param("adapter", adapter)
                .param("window", windowKey)
                .param("max", maxCalls)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Takes a call only when the last one for the adapter is at least minGap ago, counted from the moment it was
     * made rather than from a clock-hour bucket, so two runs on either side of an hour mark are still spaced.
     */
    public boolean tryAcquireSpaced(String adapter, java.time.Instant now, java.time.Duration minGap) {
        String at = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String cutoff = now.minus(minGap).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        jdbc.sql("DELETE FROM source_quota WHERE adapter = :adapter AND window_key < :old")
                .param("adapter", adapter)
                .param("old", now.minus(java.time.Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
                .update();
        return jdbc.sql("INSERT INTO source_quota (adapter, window_key, calls) SELECT :adapter, :at, 1"
                        + " WHERE NOT EXISTS (SELECT 1 FROM source_quota WHERE adapter = :adapter AND window_key > :cutoff)"
                        + " ON CONFLICT (adapter, window_key) DO NOTHING RETURNING calls")
                .param("adapter", adapter)
                .param("at", at)
                .param("cutoff", cutoff)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
