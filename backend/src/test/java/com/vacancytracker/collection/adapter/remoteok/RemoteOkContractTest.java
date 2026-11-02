package com.vacancytracker.collection.adapter.remoteok;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.support.TestHttp;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RemoteOkContractTest extends AdapterContractTest {

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper mapper;

    private HttpServer healthy;
    private HttpServer failing;

    @BeforeEach
    void start() throws IOException {
        healthy = TestHttp.serve(Map.of("/api", new TestHttp.Route(200, TestHttp.fixture(RemoteOkContractTest.class, "remoteok.json"))));
        failing = TestHttp.serve(Map.of("/api", new TestHttp.Route(503, "")));
    }

    @AfterEach
    void stop() {
        healthy.stop(0);
        failing.stop(0);
    }

    @Override
    protected SourceAdapter healthyAdapter() {
        return adapter(healthy, true);
    }

    @Override
    protected SourceAdapter failingAdapter() {
        return adapter(failing, true);
    }

    private SourceAdapter adapter(HttpServer server, boolean enabled) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new RemoteOkAdapter(fetcher, mapper, enabled, TestHttp.base(server));
    }

    @Test
    void skipsTheLegalNoticeAndKeepsTheRemoteOkLinkForAttribution() {
        var postings = healthyAdapter().fetch().postings();

        assertThat(postings).hasSize(2);
        var first = postings.get(0);
        assertThat(first.externalId()).isEqualTo("1001");
        assertThat(first.url()).isEqualTo("https://remoteok.com/remote-jobs/remote-backend-engineer-acme-1001");
        assertThat(first.title()).isEqualTo("Backend Engineer");
        assertThat(first.company()).isEqualTo("Acme");
        assertThat(first.description()).isEqualTo("Build APIs.\n\nApply: https://acme.example/apply/1001");
        assertThat(first.salaryMin()).isEqualByComparingTo("70000");
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.postedAt()).isEqualTo(Instant.ofEpochSecond(1793620000));
        assertThat(postings.get(1).salaryMin()).isNull();
        assertThat(postings.get(1).currency()).isNull();
    }

    @Test
    void aDisabledSourceAsksNothing() {
        healthy.stop(0);
        assertThat(adapter(healthy, false).fetch().unchanged()).isTrue();
    }
}
