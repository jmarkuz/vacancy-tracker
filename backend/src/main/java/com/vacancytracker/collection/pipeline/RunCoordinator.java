package com.vacancytracker.collection.pipeline;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single-run lock and background execution (T-044, ADR-006). A trigger while a run is active returns
 * that run instead of starting another. On shutdown the current run is asked to stop; if it does not
 * finish in time it is marked interrupted.
 *
 * <p>A watchdog gives every run a longest duration (T-092, F7): a source that stalls where no timeout reaches would
 * otherwise keep the lock until a restart. When the time is up the run is marked interrupted, its thread is
 * interrupted and abandoned, and the next trigger starts on a fresh thread.
 */
@Component
public class RunCoordinator {

    /** started is false when the returned run was already active. */
    public record Started(UUID runId, boolean started) {
    }

    private final CollectionPipeline pipeline;
    private final RunRepository runs;
    private final Clock clock;
    private static final Logger LOG = LoggerFactory.getLogger(RunCoordinator.class);

    private final Duration shutdownGrace;
    private final Duration maxRun;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "collection-run-watchdog");
        t.setDaemon(true);
        return t;
    });
    private ExecutorService executor = newExecutor();
    private UUID active;
    private Future<?> activeTask;
    private ScheduledFuture<?> activeTimer;

    @Autowired
    public RunCoordinator(CollectionPipeline pipeline, RunRepository runs, Clock clock,
                          @Value("${app.collection.max-run-minutes:30}") long maxRunMinutes) {
        this(pipeline, runs, clock, Duration.ofSeconds(30), Duration.ofMinutes(maxRunMinutes));
    }

    RunCoordinator(CollectionPipeline pipeline, RunRepository runs, Clock clock, Duration shutdownGrace) {
        this(pipeline, runs, clock, shutdownGrace, Duration.ofMinutes(30));
    }

    RunCoordinator(CollectionPipeline pipeline, RunRepository runs, Clock clock, Duration shutdownGrace, Duration maxRun) {
        this.pipeline = pipeline;
        this.runs = runs;
        this.clock = clock;
        this.shutdownGrace = shutdownGrace;
        this.maxRun = maxRun;
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "collection-run");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized Started start(String trigger) {
        return start(trigger, null);
    }

    /** scope is an adapter id for a single-source run; null runs all sources. */
    public synchronized Started start(String trigger, String scope) {
        if (active != null) {
            return new Started(active, false);
        }
        UUID runId = runs.start(trigger, clock.instant(), scope);
        active = runId;
        activeTask = executor.submit(() -> {
            try {
                pipeline.execute(runId, scope);
            } finally {
                clearActive(runId);
            }
        });
        activeTimer = watchdog.schedule(() -> abandon(runId), maxRun.toMillis(), TimeUnit.MILLISECONDS);
        return new Started(runId, true);
    }

    public synchronized Optional<UUID> activeRun() {
        return Optional.ofNullable(active);
    }

    private synchronized void clearActive(UUID runId) {
        if (runId.equals(active)) {
            active = null;
            activeTask = null;
            if (activeTimer != null) {
                activeTimer.cancel(false);
                activeTimer = null;
            }
        }
    }

    /** The watchdog fired: the run took longer than allowed. Closes it, frees the lock and moves on to a new thread. */
    private synchronized void abandon(UUID runId) {
        if (!runId.equals(active)) {
            return; // it finished just before the timer
        }
        LOG.warn("Run {} did not finish within {} minutes and is marked interrupted", runId, maxRun.toMinutes());
        try {
            pipeline.abandon(runId);
        } catch (org.springframework.dao.DataAccessException e) {
            LOG.warn("The run could not be closed: {}", e.getClass().getSimpleName());
        }
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        // A thread stuck where an interrupt does not reach would block every later run on a single-thread executor.
        executor.shutdownNow();
        executor = newExecutor();
        active = null;
        activeTask = null;
        activeTimer = null;
    }

    @PreDestroy
    public void shutdown() {
        pipeline.requestStop();
        watchdog.shutdownNow();
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
