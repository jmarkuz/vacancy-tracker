package com.vacancytracker.collection.pipeline;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A run that was "running" when the application stopped (a crash, a kill) never finishes. At start-up nothing can be
 * running from the earlier process, so those rows are marked interrupted and the Refresh button and the run list show
 * the truth. A run that already began in this process (a scheduled one can start before the application is ready) is
 * not touched: only runs that began before this process booted are swept (F13).
 */
@Component
public class StaleRunSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(StaleRunSweeper.class);

    private final RunRepository runs;
    private final Clock clock;
    private final boolean enabled;
    /** When this process began; a run that started later is alive. */
    private final Instant bootedAt;

    public StaleRunSweeper(RunRepository runs, Clock clock,
                           @Value("${app.collection.sweep-stale-runs-on-startup:true}") boolean enabled) {
        this.runs = runs;
        this.clock = clock;
        this.enabled = enabled;
        this.bootedAt = clock.instant();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) {
            int swept = runs.interruptStale(clock.instant(), bootedAt);
            if (swept > 0) {
                LOG.warn("{} run(s) left unfinished by the last shutdown were marked interrupted", swept);
            }
        }
    }
}
