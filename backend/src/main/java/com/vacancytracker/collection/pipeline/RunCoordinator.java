package com.vacancytracker.collection.pipeline;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single-run lock and background execution (T-044, ADR-006). A trigger while a run is active returns
 * that run instead of starting another. On shutdown the current run is asked to stop; if it does not
 * finish in time it is marked interrupted.
 */
@Component
public class RunCoordinator {

    /** started is false when the returned run was already active. */
    public record Started(UUID runId, boolean started) {
    }

    private final CollectionPipeline pipeline;
    private final RunRepository runs;
    private final Clock clock;
    private final Duration shutdownGrace;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "collection-run");
        t.setDaemon(true);
        return t;
    });
    private UUID active;

    @Autowired
    public RunCoordinator(CollectionPipeline pipeline, RunRepository runs, Clock clock) {
        this(pipeline, runs, clock, Duration.ofSeconds(30));
    }

    RunCoordinator(CollectionPipeline pipeline, RunRepository runs, Clock clock, Duration shutdownGrace) {
        this.pipeline = pipeline;
        this.runs = runs;
        this.clock = clock;
        this.shutdownGrace = shutdownGrace;
    }

    public synchronized Started start(String trigger) {
        if (active != null) {
            return new Started(active, false);
        }
        UUID runId = runs.start(trigger, clock.instant());
        active = runId;
        executor.submit(() -> {
            try {
                pipeline.execute(runId);
            } finally {
                clearActive(runId);
            }
        });
        return new Started(runId, true);
    }

    public synchronized Optional<UUID> activeRun() {
        return Optional.ofNullable(active);
    }

    private synchronized void clearActive(UUID runId) {
        if (runId.equals(active)) {
            active = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        pipeline.requestStop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                // Mark first: a late finish from the interrupted thread then finds no running row.
                activeRun().ifPresent(id -> runs.finish(id, "interrupted", clock.instant()));
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
