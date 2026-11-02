package com.vacancytracker.collection.adapter.workingnomads;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.collection.http.UpstreamException;
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
class WorkingNomadsContractTest extends AdapterContractTest {

    private static final String ROUTE = "/api/exposed_jobs/";

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
        healthy = TestHttp.serve(Map.of(ROUTE, new TestHttp.Route(200, TestHttp.fixture(WorkingNomadsContractTest.class, "workingnomads.json"))));
        failing = TestHttp.serve(Map.of(ROUTE, new TestHttp.Route(500, "")));
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

    private WorkingNomadsAdapter adapter(HttpServer server) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new WorkingNomadsAdapter(fetcher, mapper, true, TestHttp.base(server));
    }

    @Test
    void mapsJobsAndSkipsOnesWithoutATitle() {
        var postings = healthyAdapter().fetch().postings();

        assertThat(postings).extracting(p -> p.title()).containsExactly("Data Engineer", "Support Lead");
        var first = postings.get(0);
        assertThat(first.externalId()).isEqualTo("https://www.workingnomads.com/job/go/5001/");
        assertThat(first.company()).isEqualTo("Gamma");
        assertThat(first.description()).isEqualTo("Pipelines with Python.");
        assertThat(first.workMode()).isEqualTo("remote");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-01T13:30:00Z"));
    }

    @Test
    void anAnswerThatIsNotAListIsAFailure() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> adapter(healthy).parse("{\"jobs\":[]}")))
                .isInstanceOf(UpstreamException.class);
    }
}
