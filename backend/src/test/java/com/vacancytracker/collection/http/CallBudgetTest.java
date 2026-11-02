package com.vacancytracker.collection.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CallBudgetTest extends AbstractIntegrationTest {

    @Autowired
    private CallBudget budget;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE source_quota");
    }

    @Test
    void spacedCallsAreCountedFromTheLastCallNotFromTheClockHour() {
        Instant first = Instant.parse("2026-11-02T10:59:30Z");

        assertThat(budget.tryAcquireSpaced("x", first, Duration.ofHours(1))).isTrue();
        // Past the hour mark but only 40 seconds later: still too soon.
        assertThat(budget.tryAcquireSpaced("x", Instant.parse("2026-11-02T11:00:10Z"), Duration.ofHours(1))).isFalse();
        assertThat(budget.tryAcquireSpaced("x", Instant.parse("2026-11-02T11:59:31Z"), Duration.ofHours(1))).isTrue();
    }

    @Test
    void anotherAdapterHasItsOwnSpacing() {
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        assertThat(budget.tryAcquireSpaced("a", now, Duration.ofHours(1))).isTrue();
        assertThat(budget.tryAcquireSpaced("b", now, Duration.ofHours(1))).isTrue();
    }
}
