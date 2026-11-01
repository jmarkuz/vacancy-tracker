package com.vacancytracker.collection.pipeline;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled collection with a configurable cron expression (ADR-006). A tick during a run is skipped. */
@Component
public class CollectionScheduler {

    private final RunCoordinator coordinator;

    public CollectionScheduler(RunCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "${app.collection.cron:0 0 6,18 * * *}")
    public void tick() {
        coordinator.start("schedule");
    }
}
