package com.vacancytracker.web;

import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.health.SourceHealth;
import com.vacancytracker.collection.pipeline.RunCoordinator;
import com.vacancytracker.collection.pipeline.RunRepository;
import com.vacancytracker.common.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Source health for the Sources & Runs screen, and single-source refresh (T-047). */
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    /** Consecutive failures at which the breaker is considered open (ADR-018). */
    static final int OPEN_AT_FAILURES = 5;

    private final List<SourceAdapter> adapters;
    private final SourceHealth health;
    private final RunRepository runs;
    private final RunCoordinator coordinator;
    private final Clock clock;

    public SourceController(List<SourceAdapter> adapters, SourceHealth health, RunRepository runs,
                            RunCoordinator coordinator, Clock clock) {
        this.adapters = adapters;
        this.health = health;
        this.runs = runs;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @GetMapping
    public List<SourceResponse> list() {
        Map<String, RunRepository.StatRow> latest = runs.latestStatsPerAdapter();
        Instant now = clock.instant();
        return adapters.stream()
                .sorted(Comparator.comparing(SourceAdapter::id))
                .map(a -> {
                    SourceHealth.State state = health.state(a.id()).orElse(null);
                    return SourceResponse.of(a.id(), state, latest.get(a.id()), now);
                })
                .toList();
    }

    /** Starts a run limited to one source. 202 for a new run, 200 when another run is already active. */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<RefreshResponse> refresh(@PathVariable String id) {
        boolean known = adapters.stream().anyMatch(a -> a.id().equals(id));
        if (!known) {
            throw new NotFoundException("Unknown source: " + id);
        }
        RunCoordinator.Started started = coordinator.start("manual", id);
        HttpStatus status = started.started() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new RefreshResponse(started.runId(), started.started()));
    }

    /** ok: no recent failure; warn: failing but below the breaker threshold; error: breaker open. */
    static String healthOf(SourceHealth.State state, Instant now) {
        if (state == null) {
            return "unknown";
        }
        if (state.openUntil() != null && now.isBefore(state.openUntil())) {
            return "error";
        }
        if (state.consecutiveFailures() >= OPEN_AT_FAILURES) {
            return "error";
        }
        return state.consecutiveFailures() > 0 ? "warn" : "ok";
    }

    public record RefreshResponse(java.util.UUID runId, boolean started) {
    }

    public record LastRun(int fetched, int newCount, int updated, int duplicates, int errors, long durationMs,
                          String errorMessage) {
    }

    public record SourceResponse(String id, String health, Instant lastSuccessAt, Instant lastFailureAt,
                                 int consecutiveFailures, Instant circuitOpenUntil, String lastError,
                                 LastRun lastRun) {

        static SourceResponse of(String id, SourceHealth.State state, RunRepository.StatRow stat, Instant now) {
            LastRun last = stat == null ? null : new LastRun(stat.fetched(), stat.newCount(), stat.updated(),
                    stat.duplicates(), stat.errors(), stat.durationMs(), stat.errorMessage());
            return new SourceResponse(id, healthOf(state, now),
                    state == null ? null : state.lastSuccessAt(),
                    state == null ? null : state.lastFailureAt(),
                    state == null ? 0 : state.consecutiveFailures(),
                    state == null ? null : state.openUntil(),
                    state == null ? null : state.lastError(),
                    last);
        }
    }
}
