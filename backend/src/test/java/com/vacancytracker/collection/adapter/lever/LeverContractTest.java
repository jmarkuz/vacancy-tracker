package com.vacancytracker.collection.adapter.lever;

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
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LeverContractTest extends AdapterContractTest {

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
        String body = TestHttp.fixture(LeverContractTest.class, "lever-acme.json");
        healthy = TestHttp.serve(Map.of("/v0/postings/acme?mode=json", new TestHttp.Route(200, body)));
        failing = TestHttp.serve(Map.of("/v0/postings/acme?mode=json", new TestHttp.Route(503, "")));
    }

    @AfterEach
    void stop() {
        healthy.stop(0);
        failing.stop(0);
    }

    @Override
    protected SourceAdapter healthyAdapter() {
        return adapter(healthy);
    }

    @Override
    protected SourceAdapter failingAdapter() {
        return adapter(failing);
    }

    private SourceAdapter adapter(HttpServer server) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new LeverAdapter(fetcher, mapper, List.of("acme"), TestHttp.base(server));
    }

    @Test
    void mapsLeverFieldsIncludingWorkplaceType() {
        FetchOutcome outcome = healthyAdapter().fetch();

        assertThat(outcome.postings()).hasSize(2);
        assertThat(outcome.postings().get(0).externalId()).isEqualTo("lv-1");
        assertThat(outcome.postings().get(0).location()).isEqualTo("Warsaw");
        assertThat(outcome.postings().get(0).workMode()).isEqualTo("hybrid");
        assertThat(outcome.postings().get(1).workMode()).isEqualTo("remote");
        assertThat(outcome.postings().get(0).postedAt()).isNotNull();
    }
}
