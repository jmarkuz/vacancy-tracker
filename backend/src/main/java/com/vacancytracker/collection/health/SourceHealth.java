package com.vacancytracker.collection.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Circuit breaker per adapter (ADR-018): after 5 consecutive failures the adapter is skipped for 15
 * minutes. When that window ends, the next run is a trial; a failure reopens the breaker at once,
 * a success resets it. Health is stored so it survives restarts.
 */
@Service
public class SourceHealth {

    static final int FAILURE_THRESHOLD = 5;
    static final Duration OPEN_FOR = Duration.ofMinutes(15);

    public record State(
            String adapter,
            boolean enabled,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            int consecutiveFailures,
            Instant openUntil,
            String lastError) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public SourceHealth(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean allowRequest(String adapter) {
        return state(adapter)
                .map(State::openUntil)
                .map(until -> !clock.instant().isBefore(until))
                .orElse(true);
    }

    public void recordSuccess(String adapter) {
        jdbc.sql("INSERT INTO source_state (adapter, last_success_at, consecutive_failures, open_until, last_error)"
                        + " VALUES (:adapter, :now, 0, NULL, NULL)"
                        + " ON CONFLICT (adapter) DO UPDATE SET last_success_at = :now, consecutive_failures = 0,"
                        + " open_until = NULL, last_error = NULL")
                .param("adapter", adapter)
                .param("now", at(clock.instant()))
                .update();
    }

    public void recordFailure(String adapter, String message) {
        Instant now = clock.instant();
        jdbc.sql("INSERT INTO source_state (adapter, last_failure_at, consecutive_failures, open_until, last_error)"
                        + " VALUES (:adapter, :now, 1, CASE WHEN 1 >= :threshold THEN :openUntil ELSE NULL END, :message)"
                        + " ON CONFLICT (adapter) DO UPDATE SET last_failure_at = :now,"
                        + " consecutive_failures = source_state.consecutive_failures + 1, last_error = :message,"
                        + " open_until = CASE WHEN source_state.consecutive_failures + 1 >= :threshold"
                        + " THEN :openUntil ELSE source_state.open_until END")
                .param("adapter", adapter)
                .param("now", at(now))
                .param("threshold", FAILURE_THRESHOLD)
                .param("openUntil", at(now.plus(OPEN_FOR)))
                .param("message", message)
                .update();
    }

    public Optional<State> state(String adapter) {
        return jdbc.sql("SELECT adapter, enabled, last_success_at, last_failure_at, consecutive_failures, open_until,"
                        + " last_error FROM source_state WHERE adapter = :adapter")
                .param("adapter", adapter)
                .query((rs, n) -> new State(
                        rs.getString("adapter"),
                        rs.getBoolean("enabled"),
                        instant(rs.getObject("last_success_at", OffsetDateTime.class)),
                        instant(rs.getObject("last_failure_at", OffsetDateTime.class)),
                        rs.getInt("consecutive_failures"),
                        instant(rs.getObject("open_until", OffsetDateTime.class)),
                        rs.getString("last_error")))
                .optional();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
