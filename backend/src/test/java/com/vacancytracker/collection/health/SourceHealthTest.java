package com.vacancytracker.collection.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class SourceHealthTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcTemplate template;

    private Instant now;
    private SourceHealth health;

    @BeforeEach
    void setUp() {
        template.execute("TRUNCATE source_state");
        now = Instant.parse("2026-11-01T10:00:00Z");
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        health = new SourceHealth(jdbc, clock);
    }

    @Test
    void opensAfterFiveConsecutiveFailuresAndRecoversAfterWindow() {
        for (int i = 0; i < 4; i++) {
            health.recordFailure("ats-greenhouse", "HTTP 500");
        }
        assertThat(health.allowRequest("ats-greenhouse")).isTrue();

        health.recordFailure("ats-greenhouse", "HTTP 500");
        assertThat(health.allowRequest("ats-greenhouse")).isFalse();
        assertThat(health.state("ats-greenhouse").orElseThrow().openUntil())
                .isEqualTo(now.plus(Duration.ofMinutes(15)));

        now = now.plus(Duration.ofMinutes(15));
        assertThat(health.allowRequest("ats-greenhouse")).isTrue();
    }

    @Test
    void failureDuringTrialReopensAtOnce() {
        for (int i = 0; i < 5; i++) {
            health.recordFailure("arbeitnow", "timeout");
        }
        now = now.plus(Duration.ofMinutes(16));
        health.recordFailure("arbeitnow", "timeout");
        assertThat(health.allowRequest("arbeitnow")).isFalse();
    }

    @Test
    void successResetsTheBreakerAndKeepsTheLastSuccess() {
        for (int i = 0; i < 5; i++) {
            health.recordFailure("remotive", "HTTP 503");
        }
        health.recordSuccess("remotive");

        SourceHealth.State state = health.state("remotive").orElseThrow();
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.openUntil()).isNull();
        assertThat(state.lastError()).isNull();
        assertThat(state.lastSuccessAt()).isEqualTo(now);
        assertThat(health.allowRequest("remotive")).isTrue();
    }

    @Test
    void unknownAdapterIsAllowed() {
        assertThat(health.allowRequest("never-seen")).isTrue();
    }
}
