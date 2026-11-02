package com.vacancytracker.collection.adapter.greenhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GreenhouseAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper mapper;

    private HttpServer server;
    private String base;
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/boards/acme/jobs", ex -> {
            if ("\"g1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                ex.sendResponseHeaders(304, -1);
                ex.close();
                return;
            }
            if (status != 200) {
                ex.sendResponseHeaders(status, -1);
                ex.close();
                return;
            }
            byte[] bytes = fixture("greenhouse-acme.json");
            ex.getResponseHeaders().add("ETag", "\"g1\"");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = GreenhouseAdapterTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        }
    }

    private SourceAdapter adapter(List<String> boards) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new GreenhouseAdapter(fetcher, mapper, boards, base);
    }

    @Test
    void mapsBoardJobsToRawPostingsWithPlainTextDescription() {
        FetchOutcome outcome = adapter(List.of("acme")).fetch();

        assertThat(outcome.unchanged()).isFalse();
        assertThat(outcome.postings()).hasSize(2);
        RawPosting first = outcome.postings().get(0);
        assertThat(first.adapter()).isEqualTo("ats-greenhouse");
        assertThat(first.externalId()).isEqualTo("4001");
        assertThat(first.url()).isEqualTo("https://boards.greenhouse.io/acme/jobs/4001");
        assertThat(first.title()).isEqualTo("Backend Engineer (Java)");
        assertThat(first.location()).isEqualTo("Berlin");
        assertThat(first.description()).isEqualTo("Build APIs for our platform.");
        assertThat(first.postedAt()).isNotNull();
    }

    @Test
    void everyRunReturnsTheWholeBoardBecauseNoValidatorsAreSent() {
        SourceAdapter adapter = adapter(List.of("acme"));
        adapter.fetch();

        FetchOutcome second = adapter.fetch();

        assertThat(second.unchanged()).isFalse();
        assertThat(second.postings()).isNotEmpty();
    }

    @Test
    void serverErrorFailsTheAdapter() {
        status = 503;
        assertThatThrownBy(() -> adapter(List.of("acme")).fetch())
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void noConfiguredBoardsMeansNothingToDo() {
        assertThat(adapter(List.of()).fetch().postings()).isEmpty();
    }

    @Test
    void declaresNoServerSideFields() {
        assertThat(adapter(List.of()).serverSideFields()).isEmpty();
    }
}
