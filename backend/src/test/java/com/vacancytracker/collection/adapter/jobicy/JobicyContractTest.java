package com.vacancytracker.collection.adapter.jobicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.CallBudget;
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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class JobicyContractTest extends AdapterContractTest {

    private static final String ROUTE = "/api/jobs?count=100";

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private CallBudget budget;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate template;

    private HttpServer healthy;
    private HttpServer failing;

    @BeforeEach
    void start() throws IOException {
        template.execute("TRUNCATE source_quota");
        healthy = TestHttp.serve(Map.of(ROUTE, new TestHttp.Route(200, TestHttp.fixture(JobicyContractTest.class, "jobicy.json"))));
        failing = TestHttp.serve(Map.of(ROUTE, new TestHttp.Route(503, "")));
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
        return new JobicyAdapter(fetcher, budget, mapper, clock, TestHttp.base(server), "");
    }

    @Test
    void atMostOnePassPerHour() {
        SourceAdapter adapter = healthyAdapter();
        assertThat(adapter.fetch().postings()).hasSize(1);

        var second = adapter.fetch();

        assertThat(second.unchanged()).isTrue();
        assertThat(second.postings()).isEmpty();
    }

    @Test
    void mapsJobicyFields() {
        var posting = healthyAdapter().fetch().postings().get(0);

        assertThat(posting.externalId()).isEqualTo("jb-7");
        assertThat(posting.title()).isEqualTo("Analyst");
        assertThat(posting.description()).isEqualTo("Analyse data.");
        assertThat(posting.workMode()).isEqualTo("remote");
    }
}
