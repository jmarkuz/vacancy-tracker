package com.vacancytracker.collection.adapter.arbeitnow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.support.TestHttp;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ArbeitnowContractTest extends AdapterContractTest {

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper mapper;

    private HttpServer healthy;
    private HttpServer failing;
    private HttpServer twoFullPages;

    @BeforeEach
    void start() throws IOException {
        String page1 = TestHttp.fixture(ArbeitnowContractTest.class, "arbeitnow-page1.json");
        String page2 = TestHttp.fixture(ArbeitnowContractTest.class, "arbeitnow-page2.json");
        healthy = TestHttp.serve(Map.of(
                "/api/job-board-api?page=1", new TestHttp.Route(200, page1),
                "/api/job-board-api?page=2", new TestHttp.Route(200, page2)));
        failing = TestHttp.serve(Map.of("/api/job-board-api?page=1", new TestHttp.Route(503, "")));
        twoFullPages = TestHttp.serve(Map.of(
                "/api/job-board-api?page=1", new TestHttp.Route(200, page1),
                "/api/job-board-api?page=2", new TestHttp.Route(200, page1)));
    }

    @AfterEach
    void stop() {
        healthy.stop(0);
        failing.stop(0);
        twoFullPages.stop(0);
    }

    @Override
    protected SourceAdapter healthyAdapter() {
        return adapter(healthy, 3);
    }

    @Override
    protected SourceAdapter failingAdapter() {
        return adapter(failing, 3);
    }

    private SourceAdapter adapter(HttpServer server, int maxPages) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new ArbeitnowAdapter(fetcher, mapper, TestHttp.base(server), maxPages);
    }

    @Test
    void stopsAtTheLastPageAndMapsFields() {
        FetchOutcome outcome = healthyAdapter().fetch();

        assertThat(outcome.postings()).hasSize(1);
        var posting = outcome.postings().get(0);
        assertThat(posting.externalId()).isEqualTo("an-1");
        assertThat(posting.company()).isEqualTo("Northwind");
        assertThat(posting.workMode()).isEqualTo("remote");
        assertThat(posting.description()).isEqualTo("Write services.");
        assertThat(posting.postedAt().getEpochSecond()).isEqualTo(1761900000L);
    }

    @Test
    void fetchesNoMoreThanTheConfiguredPages() {
        FetchOutcome outcome = adapter(twoFullPages, 2).fetch();

        assertThat(outcome.postings()).hasSize(2);
    }
}
