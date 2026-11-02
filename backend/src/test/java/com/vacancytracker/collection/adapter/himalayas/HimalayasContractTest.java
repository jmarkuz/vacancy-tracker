package com.vacancytracker.collection.adapter.himalayas;

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
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HimalayasContractTest extends AdapterContractTest {

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
        healthy = TestHttp.serve(Map.of(
                "/jobs/api?limit=20", new TestHttp.Route(200, TestHttp.fixture(HimalayasContractTest.class, "himalayas-page1.json"))
                , "/jobs/api?limit=20&cursor=c2", new TestHttp.Route(200, TestHttp.fixture(HimalayasContractTest.class, "himalayas-page2.json"))));
        failing = TestHttp.serve(Map.of("/jobs/api?limit=20", new TestHttp.Route(503, "")));
    }

    @AfterEach
    void stop() {
        healthy.stop(0);
        failing.stop(0);
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
        return new HimalayasAdapter(fetcher, mapper, TestHttp.base(server), maxPages, "");
    }

    @Test
    void followsTheCursorAndMapsApplyLinkAndSalary() {
        var postings = healthyAdapter().fetch().postings();

        assertThat(postings).extracting(p -> p.externalId()).containsExactly("h-1", "h-2");
        var first = postings.get(0);
        assertThat(first.url()).isEqualTo("https://himalayas.app/companies/orbit-forge/jobs/data-engineer");
        assertThat(first.location()).isEqualTo("Germany");
        assertThat(first.salaryMax()).isEqualByComparingTo("85000");
        assertThat(first.currency()).isEqualTo("EUR");
        assertThat(postings.get(1).location()).isEqualTo("Worldwide");
    }
}
