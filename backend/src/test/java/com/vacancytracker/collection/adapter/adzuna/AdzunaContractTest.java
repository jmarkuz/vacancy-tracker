package com.vacancytracker.collection.adapter.adzuna;

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
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AdzunaContractTest extends AdapterContractTest {

    private static final String ROUTE = "/v1/api/jobs/gb/search/1?results_per_page=50&app_id=id1&app_key=key1";

    @Autowired
    private ConditionalCache cache;

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
        template.execute("TRUNCATE http_cache");
        healthy = TestHttp.serve(Map.of(ROUTE, new TestHttp.Route(200,
                TestHttp.fixture(AdzunaContractTest.class, "adzuna-gb.json"), "\"az1\"")));
        failing = TestHttp.serve(Map.of(ROUTE, new TestHttp.Route(503, "")));
    }

    @AfterEach
    void stop() {
        healthy.stop(0);
        failing.stop(0);
    }

    @Override
    protected SourceAdapter healthyAdapter() {
        return adapter(healthy, "id1", "key1", "gb=GBP");
    }

    @Override
    protected SourceAdapter failingAdapter() {
        return adapter(failing, "id1", "key1", "gb=GBP");
    }

    private SourceAdapter adapter(HttpServer server, String appId, String appKey, String currencies) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new AdzunaAdapter(fetcher, mapper, TestHttp.base(server), appId, appKey, List.of("gb"), currencies, "", 1);
    }

    @Test
    void mapsResultsAndKeepsCurrencyFromConfiguration() {
        var posting = healthyAdapter().fetch().postings().get(0);

        assertThat(posting.externalId()).isEqualTo("az-9");
        assertThat(posting.company()).isEqualTo("Lumen Works");
        assertThat(posting.location()).isEqualTo("Leeds");
        assertThat(posting.salaryMin()).isEqualByComparingTo("50000");
        assertThat(posting.currency()).isEqualTo("GBP");
        assertThat(posting.salaryPeriod()).isNull();
    }

    @Test
    void keysAreNeverStoredInTheCache() {
        healthyAdapter().fetch();

        Integer stored = template.queryForObject("SELECT count(*) FROM http_cache", Integer.class);
        Integer withKeys = template.queryForObject(
                "SELECT count(*) FROM http_cache WHERE url LIKE '%app_key%' OR url LIKE '%key1%'", Integer.class);
        assertThat(stored).isEqualTo(1);
        assertThat(withKeys).isZero();
    }

    @Test
    void anUnknownCountryCodeIsRefusedBeforeAnyRequest() {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        var adapter = new AdzunaAdapter(fetcher, mapper, TestHttp.base(healthy), "id1", "key1",
                List.of("gb", "../x"), "gb=GBP", "", 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(adapter::fetch)
                .isInstanceOf(com.vacancytracker.collection.http.UpstreamException.class)
                .hasMessageContaining("'../x'");
        assertThat(template.queryForObject("SELECT count(*) FROM http_cache", Integer.class)).isZero();
    }

    @Test
    void withoutKeysOrCountriesThereIsNothingToDo() {
        HttpServer any = healthy;
        var outcome = adapter(any, "", "", "gb=GBP").fetch();

        assertThat(outcome.unchanged()).isTrue();
        assertThat(outcome.postings()).isEmpty();
    }

    @Test
    void parsesCurrencyConfiguration() {
        assertThat(AdzunaAdapter.parseCurrencies("gb=gbp, us = usd,bad")).containsEntry("gb", "GBP").containsEntry("us", "USD").hasSize(2);
    }
}
