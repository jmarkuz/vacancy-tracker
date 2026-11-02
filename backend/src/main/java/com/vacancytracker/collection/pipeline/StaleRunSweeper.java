package com.vacancytracker.collection.pipeline;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A run that was "running" when the application stopped (a crash, a kill) never finishes. At start-up nothing can be
 * running, so those rows are marked interrupted and the Refresh button and the run list show the truth.
 */
@Component
public class StaleRunSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(StaleRunSweeper.class);

    private final RunRepository runs;
    private final Clock clock;
    private final boolean enabled;

    public StaleRunSweeper(RunRepository runs, Clock clock,
                           @Value("${app.collection.sweep-stale-runs-on-startup:true}") boolean enabled) {
        this.runs = runs;
        this.clock = clock;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) {
            int swept = runs.interruptStale(clock.instant());
            if (swept > 0) {
                LOG.warn("{} run(s) left unfinished by the last shutdown were marked interrupted", swept);
            }
        }
    }
}
