package com.vacancytracker.collection.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunEventBusTest {

    @Test
    void keepsEventsInOrderForLateSubscribers() {
        RunEventBus bus = new RunEventBus();
        UUID run = UUID.randomUUID();

        bus.publish(run, "started", "x");
        bus.publish(run, "source", "a");
        bus.publish(run, "done", "ok");

        assertThat(bus.snapshot(run)).extracting(RunEventBus.RunEvent::type)
                .containsExactly("started", "source", "done");
        assertThat(bus.knows(run)).isTrue();
    }

    @Test
    void unknownRunHasNoEvents() {
        RunEventBus bus = new RunEventBus();
        assertThat(bus.knows(UUID.randomUUID())).isFalse();
        assertThat(bus.snapshot(UUID.randomUUID())).isEmpty();
    }

    @Test
    void subscribingToFinishedRunAddsNoLiveEmitter() {
        RunEventBus bus = new RunEventBus();
        UUID run = UUID.randomUUID();
        bus.publish(run, "done", "ok");

        assertThat(bus.subscribe(run)).isNotNull();
        assertThat(bus.snapshot(run)).hasSize(1);
    }

    @Test
    void forgetsTheOldestFinishedRunsBeyondTheLimit() {
        RunEventBus bus = new RunEventBus();
        UUID first = UUID.randomUUID();
        bus.publish(first, "done", "ok");
        for (int i = 0; i < RunEventBus.KEEP_FINISHED; i++) {
            bus.publish(UUID.randomUUID(), "done", "ok");
        }

        assertThat(bus.knows(first)).isFalse();
    }
}
