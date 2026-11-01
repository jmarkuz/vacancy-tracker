package com.vacancytracker.collection.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.dedup.Deduplicator;
import com.vacancytracker.collection.health.SourceHealth;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.support.MutableClock;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ExpiryAndRunLockTest extends AbstractIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcTemplate template;

    @Autowired
    private VacancyRepository vacancies;

    @Autowired
    private RunRepository runs;

    @Autowired
    private Deduplicator deduplicator;

    @Autowired
    private IngestService ingest;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private com.vacancytracker.matching.MatchService matchService;

    private MutableClock clock;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE run, source_state, http_cache CASCADE");
        clock = new MutableClock(START);
    }

    private static SourceAdapter adapter(String id, Supplier<FetchOutcome> fetch) {
        return new SourceAdapter() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> serverSideFields() {
                return Set.of();
            }

            @Override
            public FetchOutcome fetch() {
                return fetch.get();
            }
        };
    }

    private static RawPosting posting(String ext) {
        return new RawPosting("exp", ext, "https://ex.com/" + ext, "Data Engineer " + ext, "Acme", "Berlin",
                null, "Plain.", null, null, null, null, null);
    }

    private CollectionPipeline pipeline(List<SourceAdapter> adapters) {
        SourceHealth health = new SourceHealth(jdbc, clock);
        IngestService ingestService = new IngestService(deduplicator, vacancies);
        ExpiryService expiry = new ExpiryService(jdbc, vacancies, clock, transactions, 3);
        return new CollectionPipeline(adapters, health, ingestService, runs, vacancies, expiry, matchService, new RunEventBus(), clock);
    }

    private String statusOf(String title) {
        return template.queryForObject("SELECT status FROM vacancy WHERE title = ?", String.class, title);
    }

    @Test
    void vacancyExpiresAfterThreeSuccessfulRunsWithoutSighting() {
        AtomicBoolean seen = new AtomicBoolean(true);
        SourceAdapter source = adapter("exp", () -> FetchOutcome.of(seen.get() ? List.of(posting("1")) : List.of()));
        CollectionPipeline pipeline = pipeline(List.of(source));
        pipeline.run("manual");
        seen.set(false);

        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofHours(1));
            pipeline.run("manual");
        }
        assertThat(statusOf("Data Engineer 1")).isEqualTo("New");

        clock.advance(Duration.ofHours(1));
        pipeline.run("manual");

        assertThat(statusOf("Data Engineer 1")).isEqualTo("Expired");
        assertThat(template.queryForObject(
                "SELECT note FROM status_event WHERE to_status = 'Expired'", String.class)).isEqualTo("auto-expired");
    }

    @Test
    void aVacancyFoundOnlyInAnAlertEmailNeverExpires() {
        AtomicBoolean seen = new AtomicBoolean(true);
        RawPosting fromMail = new RawPosting("email-example", "1", "https://ex.com/mail1", "Mail Engineer", "Acme", "Berlin",
                null, "Plain.", null, null, null, null, null);
        SourceAdapter source = adapter("email-example", () -> FetchOutcome.of(seen.get() ? List.of(fromMail) : List.of()));
        CollectionPipeline pipeline = pipeline(List.of(source));
        pipeline.run("manual");
        seen.set(false);

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofHours(1));
            pipeline.run("manual");
        }

        assertThat(statusOf("Mail Engineer")).isEqualTo("New");
    }

    @Test
    void aRunLogsOneLinePerSourceAndOneSummaryWithItsNumbers(org.springframework.boot.test.system.CapturedOutput output) {
        SourceAdapter good = adapter("log-good", () -> FetchOutcome.of(List.of(posting("1"), posting("2"))));
        SourceAdapter bad = adapter("log-bad", () -> {
            throw new com.vacancytracker.collection.http.UpstreamException("HTTP 503 after 3 attempts");
        });

        pipeline(List.of(good, bad)).run("manual");

        assertThat(output.getAll()).contains("Source log-good finished: fetched 2, new 1, updated 0, duplicates 1, errors 0")
                .contains("Source log-bad failed: fetched 0, new 0, updated 0, duplicates 0, errors 1")
                .contains("(HTTP 503 after 3 attempts)")
                .containsPattern("Run [0-9a-f-]{36} completed_with_errors: 2 sources, fetched 2, new 1, updated 0, failed sources 1, \\d+ ms");
    }

    @Test
    void failingSourceDoesNotCountTowardExpiry() {
        AtomicBoolean fail = new AtomicBoolean(false);
        SourceAdapter source = adapter("exp", () -> {
            if (fail.get()) {
                throw new UpstreamException("HTTP 503 after 4 attempts");
            }
            return fail.get() ? FetchOutcome.of(List.of()) : FetchOutcome.of(List.of(posting("2")));
        });
        CollectionPipeline pipeline = pipeline(List.of(source));
        pipeline.run("manual");
        fail.set(true);

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofHours(1));
            pipeline.run("manual");
        }

        assertThat(statusOf("Data Engineer 2")).isEqualTo("New");
    }

    @Test
    void secondTriggerWhileRunningReturnsTheActiveRun() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SourceAdapter slow = adapter("slow", () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FetchOutcome.of(List.of());
        });
        RunCoordinator coordinator = new RunCoordinator(pipeline(List.of(slow)), runs, clock, Duration.ofSeconds(5));

        RunCoordinator.Started first = coordinator.start("manual");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        RunCoordinator.Started second = coordinator.start("schedule");

        assertThat(first.started()).isTrue();
        assertThat(second.started()).isFalse();
        assertThat(second.runId()).isEqualTo(first.runId());
        release.countDown();
        coordinator.shutdown();
    }

    @Test
    void shutdownMarksARunThatDoesNotFinishInTimeAsInterrupted() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        SourceAdapter stuck = adapter("stuck", () -> {
            entered.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException("interrupted during shutdown");
            }
            return FetchOutcome.of(List.of());
        });
        RunCoordinator coordinator = new RunCoordinator(pipeline(List.of(stuck)), runs, clock, Duration.ofMillis(200));

        RunCoordinator.Started started = coordinator.start("manual");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.shutdown();

        assertThat(runs.findRun(started.runId()).orElseThrow().status()).isEqualTo("interrupted");
    }
}
