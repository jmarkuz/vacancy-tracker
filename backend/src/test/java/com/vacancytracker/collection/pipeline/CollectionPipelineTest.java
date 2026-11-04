package com.vacancytracker.collection.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.dedup.Deduplicator;
import com.vacancytracker.collection.health.SourceHealth;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
class CollectionPipelineTest extends AbstractIntegrationTest {

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
    private PlatformTransactionManager transactions;

    @Autowired
    private com.vacancytracker.matching.MatchService matchService;

    @Autowired
    private com.vacancytracker.matching.SearchProfileService profileService;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE run, source_state, http_cache, search_profile CASCADE");
    }

    private static RawPosting posting(String adapter, String ext, String url, String title, String company,
                                      String location) {
        return new RawPosting(adapter, ext, url, title, company, location, null, "Plain description.", null, null,
                null, null, Instant.parse("2026-10-30T09:00:00Z"));
    }

    private static SourceAdapter fixed(String id, Supplier<FetchOutcome> fetch) {
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

    private CollectionPipeline pipeline(List<SourceAdapter> adapters) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("UTC"));
        SourceHealth health = new SourceHealth(jdbc, clock);
        IngestService ingest = new IngestService(deduplicator, vacancies);
        ExpiryService expiry = new ExpiryService(jdbc, vacancies, clock, transactions, 3);
        return new CollectionPipeline(adapters, health, ingest, runs, vacancies, expiry, matchService, new RunEventBus(), clock);
    }

    @Test
    void storesNewPostingsIsolatesFailingAdapterAndRecordsStats() {
        SourceAdapter healthy = fixed("fake-ok", () -> FetchOutcome.of(List.of(
                posting("fake-ok", "1", "https://ex.com/a?utm_source=x", "Java Developer", "Acme", "Berlin"),
                posting("fake-ok", "2", "https://ex.com/b", "Data Engineer", "Acme", "Berlin"))));
        SourceAdapter broken = fixed("fake-broken", () -> {
            throw new UpstreamException("HTTP 503 after 4 attempts");
        });

        var summary = pipeline(List.of(broken, healthy)).run("manual");

        assertThat(summary.status()).isEqualTo("completed_with_errors");
        var ok = summary.adapters().stream().filter(a -> a.adapter().equals("fake-ok")).findFirst().orElseThrow();
        assertThat(ok.stats().newCount).isEqualTo(2);
        assertThat(ok.error()).isNull();
        var failed = summary.adapters().stream().filter(a -> a.adapter().equals("fake-broken")).findFirst().orElseThrow();
        assertThat(failed.stats().errors).isEqualTo(1);
        assertThat(failed.error()).contains("503");
        assertThat(runs.status(summary.runId())).isEqualTo("completed_with_errors");
    }

    @Test
    void newPostingsAreScoredDuringTheRunAndLowScoresAreHidden() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        profileService.create(new com.vacancytracker.matching.ProfileInput("Main", true, List.of("all"), null,
                com.vacancytracker.matching.Thresholds.defaults(), List.of(new com.vacancytracker.matching.Criterion(
                "kw", "title", "contains_any", mapper.readTree("[\"java\"]"), "prefer", 5, null, "Java"))));
        SourceAdapter source = fixed("fake-ok", () -> FetchOutcome.of(List.of(
                posting("fake-ok", "1", "https://ex.com/a", "Java Developer", "Acme", "Berlin"),
                posting("fake-ok", "2", "https://ex.com/b", "Cobol Developer", "Acme", "Berlin"))));

        pipeline(List.of(source)).run("manual");

        assertThat(jdbc.sql("SELECT count(*) FROM vacancy_match").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT match_score FROM vacancy WHERE title = 'Java Developer'").query(Integer.class).single()).isEqualTo(100);
        assertThat(jdbc.sql("SELECT status FROM vacancy WHERE title = 'Java Developer'").query(String.class).single()).isEqualTo("New");
        assertThat(jdbc.sql("SELECT status FROM vacancy WHERE title = 'Cobol Developer'").query(String.class).single()).isEqualTo("Hidden");
    }

    @Test
    void secondRunOfSamePostingsIsDuplicatesNotNewVacancies() {
        List<RawPosting> batch = List.of(
                posting("fake-ok", "1", "https://ex.com/a", "Java Developer", "Acme", "Berlin"));
        var pipeline = pipeline(List.of(fixed("fake-ok", () -> FetchOutcome.of(batch))));
        pipeline.run("schedule");

        var second = pipeline.run("schedule");

        assertThat(second.status()).isEqualTo("completed");
        assertThat(second.adapters().get(0).stats().newCount).isZero();
        assertThat(second.adapters().get(0).stats().duplicates).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM vacancy").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void sameVacancyFromTwoSourcesIsMergedWithTwoSourceRows() {
        pipeline(List.of(fixed("fake-a", () -> FetchOutcome.of(List.of(
                posting("fake-a", "x1", "https://ex.com/job/9?utm_medium=feed", "Platform Engineer", "Northwind", "Remote")))))).run("manual");

        var summary = pipeline(List.of(fixed("fake-b", () -> FetchOutcome.of(List.of(
                posting("fake-b", "y9", "https://ex.com/job/9", "Platform Engineer", "Northwind", "Remote")))))).run("manual");

        assertThat(summary.adapters().get(0).stats().duplicates).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM vacancy_source").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void unchangedSourceKeepsItsVacanciesCurrent() {
        pipeline(List.of(fixed("fake-ok", () -> FetchOutcome.of(List.of(
                posting("fake-ok", "1", "https://ex.com/a", "Java Developer", "Acme", "Berlin")))))).run("manual");

        var summary = pipeline(List.of(fixed("fake-ok", FetchOutcome::noChange))).run("manual");

        assertThat(summary.adapters().get(0).stats().fetched).isZero();
        assertThat(summary.adapters().get(0).error()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM vacancy").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void openCircuitSkipsTheAdapterAndSaysSo() {
        SourceHealth health = new SourceHealth(jdbc, Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("UTC")));
        for (int i = 0; i < 5; i++) {
            health.recordFailure("fake-down", "HTTP 500");
        }
        var summary = pipeline(List.of(fixed("fake-down", () -> FetchOutcome.of(List.of())))).run("manual");

        assertThat(summary.adapters().get(0).error()).contains("Circuit open");
    }

    private static RawPosting postingDated(String ext, Instant postedAt) {
        return new RawPosting("fake-ok", ext, "https://ex.com/dated", "Java Developer", "Acme", "Berlin", null, "Plain description.",
                null, null, null, null, postedAt);
    }

    private Instant storedPostedAt() {
        var at = jdbc.sql("SELECT posted_at FROM vacancy WHERE title = 'Java Developer'").query(java.time.OffsetDateTime.class).optional();
        return at.map(java.time.OffsetDateTime::toInstant).orElse(null);
    }

    @Test
    void thePostingDateIsStoredAndTheEarliestDateSeenIsKept() {
        Instant first = Instant.parse("2026-10-20T09:00:00Z");
        List<RawPosting>[] batch = new List[] {List.of(postingDated("1", first))};
        var pipeline = pipeline(List.of(fixed("fake-ok", () -> FetchOutcome.of(batch[0]))));

        pipeline.run("manual");
        assertThat(storedPostedAt()).isEqualTo(first);

        // A source that republishes the posting with a fresh date must not make it look new.
        batch[0] = List.of(postingDated("1", Instant.parse("2026-10-31T09:00:00Z")));
        pipeline.run("manual");
        assertThat(storedPostedAt()).isEqualTo(first);

        // A source that knows an earlier date moves it back.
        batch[0] = List.of(postingDated("1", Instant.parse("2026-10-10T09:00:00Z")));
        pipeline.run("manual");
        assertThat(storedPostedAt()).isEqualTo(Instant.parse("2026-10-10T09:00:00Z"));

        // A source that gives no date never erases it.
        batch[0] = List.of(postingDated("1", null));
        pipeline.run("manual");
        assertThat(storedPostedAt()).isEqualTo(Instant.parse("2026-10-10T09:00:00Z"));
    }

    @Test
    void aPostingWithoutADateStoresNone() {
        var pipeline = pipeline(List.of(fixed("fake-ok", () -> FetchOutcome.of(List.of(postingDated("1", null))))));

        pipeline.run("manual");

        assertThat(storedPostedAt()).isNull();
    }

    @Test
    void aSecondDateOnlyDoesNotCountAsAnUpdate() {
        List<RawPosting>[] batch = new List[] {List.of(postingDated("1", Instant.parse("2026-10-20T09:00:00Z")))};
        var pipeline = pipeline(List.of(fixed("fake-ok", () -> FetchOutcome.of(batch[0]))));
        pipeline.run("manual");
        batch[0] = List.of(postingDated("1", Instant.parse("2026-10-05T09:00:00Z")));

        var second = pipeline.run("manual");

        assertThat(second.adapters().get(0).stats().updated).isZero();
        assertThat(second.adapters().get(0).stats().duplicates).isEqualTo(1);
    }
}
