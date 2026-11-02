package com.vacancytracker.collection.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.matching.rates.ExchangeRateRepository;
import com.vacancytracker.matching.rates.ExchangeRateService;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.support.TestHttp;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class EcbRateRefresherTest extends AbstractIntegrationTest {

    private static final String PATH = "/stats/eurofxref/eurofxref-daily.xml";

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private ExchangeRateService service;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate template;

    private HttpServer healthy;
    private HttpServer failing;
    private HttpServer garbage;

    @BeforeEach
    void start() throws IOException {
        template.execute("TRUNCATE exchange_rate, http_cache");
        service.reload();
        healthy = TestHttp.serve(Map.of(PATH, new TestHttp.Route(200,
                TestHttp.fixture(EcbRateRefresherTest.class, "ecb-sample.xml"), "\"ecb1\"")));
        failing = TestHttp.serve(Map.of(PATH, new TestHttp.Route(503, "")));
        garbage = TestHttp.serve(Map.of(PATH, new TestHttp.Route(200, "<html>maintenance</html>")));
    }

    @AfterEach
    void stop() {
        healthy.stop(0);
        failing.stop(0);
        garbage.stop(0);
    }

    private EcbRateRefresher refresher(HttpServer server) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new EcbRateRefresher(fetcher, repository, service, clock, TestHttp.base(server) + PATH);
    }

    @Test
    void storesTheFeedAndMakesConversionAvailable() {
        boolean updated = refresher(healthy).refresh();

        assertThat(updated).isTrue();
        assertThat(service.rateDate()).contains(LocalDate.of(2026, 10, 30));
        assertThat(service.convert(new BigDecimal("1117.2"), "USD", "EUR").orElseThrow()).isEqualByComparingTo("1000");
        assertThat(template.queryForObject("SELECT DISTINCT source FROM exchange_rate", String.class))
                .isEqualTo("European Central Bank");
    }

    @Test
    void aSecondRefreshOfTheSameFeedReplacesTheRatesInPlace() {
        EcbRateRefresher refresher = refresher(healthy);
        refresher.refresh();

        assertThat(refresher.refresh()).isTrue();
        assertThat(template.queryForObject("SELECT count(*) FROM exchange_rate", Integer.class)).isEqualTo(4);
    }

    @Test
    void aFailingFeedKeepsTheLastKnownRates() {
        refresher(healthy).refresh();

        assertThatThrownBy(() -> refresher(failing).refresh()).isInstanceOf(UpstreamException.class);
        refresher(failing).refreshQuietly();

        assertThat(service.convert(new BigDecimal("1117.2"), "USD", "EUR").orElseThrow()).isEqualByComparingTo("1000");
    }

    @Test
    void aDocumentThatIsNotAFeedIsRejectedAndNothingIsStored() {
        assertThatThrownBy(() -> refresher(garbage).refresh())
                .isInstanceOf(UpstreamException.class).hasMessageContaining("no dated rates");

        assertThat(template.queryForObject("SELECT count(*) FROM exchange_rate", Integer.class)).isZero();
        assertThat(service.rateDate()).isEmpty();
    }
}
