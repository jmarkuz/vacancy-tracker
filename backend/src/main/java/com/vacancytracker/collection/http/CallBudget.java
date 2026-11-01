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
}
