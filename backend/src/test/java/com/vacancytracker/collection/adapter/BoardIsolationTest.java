package com.vacancytracker.collection.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.ashby.AshbyAdapter;
import com.vacancytracker.collection.adapter.greenhouse.GreenhouseAdapter;
import com.vacancytracker.collection.adapter.lever.LeverAdapter;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.collection.http.UpstreamException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * One company board that cannot be read must not stop the other boards of the same source (Greenhouse, Lever, Ashby).
 * Runs against a local server and needs no database: these reads send no validators, so the cache is never used.
 * Board names say how the local server answers: "good..." serves the fixture, "broken" answers HTTP 500, "gone" HTTP 404,
 * "garbled" a body that is not JSON.
 */
class BoardIsolationTest {

    enum Source {
        GREENHOUSE("/v1/boards/", "greenhouse-acme.json", "Greenhouse board") {
            @Override
            SourceAdapter adapter(HttpFetcher fetcher, ObjectMapper mapper, List<String> boards, String base) {
                return new GreenhouseAdapter(fetcher, mapper, boards, base);
            }
        },
        LEVER("/v0/postings/", "lever-acme.json", "Lever site") {
            @Override
            SourceAdapter adapter(HttpFetcher fetcher, ObjectMapper mapper, List<String> boards, String base) {
                return new LeverAdapter(fetcher, mapper, boards, base);
            }
        },
        ASHBY("/posting-api/job-board/", "ashby-acme.json", "Ashby board") {
            @Override
            SourceAdapter adapter(HttpFetcher fetcher, ObjectMapper mapper, List<String> boards, String base) {
                return new AshbyAdapter(fetcher, mapper, boards, base);
            }
        };

        final String pathPrefix;
        final String fixture;
        final String label;

        Source(String pathPrefix, String fixture, String label) {
            this.pathPrefix = pathPrefix;
            this.fixture = fixture;
            this.label = label;
        }

        abstract SourceAdapter adapter(HttpFetcher fetcher, ObjectMapper mapper, List<String> boards, String base);
    }

    private HttpServer server;
    private String base;
    private Source serving;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String board = path.substring(serving.pathPrefix.length()).split("/")[0];
            if (board.startsWith("good")) {
                byte[] body = fixture(serving.fixture);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } else if (board.startsWith("garbled")) {
                byte[] body = "this is not json".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } else {
                ex.sendResponseHeaders(board.startsWith("gone") ? 404 : 500, -1);
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = BoardIsolationTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        }
    }

    private SourceAdapter adapter(Source source, String... boards) {
        serving = source;
        HttpFetcher fetcher = new HttpFetcher(null, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, Clock.systemUTC(), ms -> { }), ms -> { });
        return source.adapter(fetcher, new ObjectMapper(), List.of(boards), base);
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void aBoardThatAnswersWithAnErrorDoesNotStopTheOthers(Source source) {
        int one = adapter(source, "good").fetch().postings().size();

        FetchOutcome outcome = adapter(source, "broken", "good").fetch();

        assertThat(outcome.postings()).as("the postings of the readable board are kept").hasSize(one);
        assertThat(outcome.warning()).startsWith("1 of 2 " + source.label + "s could not be read.").contains("broken");
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void aMissingBoardAndOneWithBrokenJsonAreBothSkipped(Source source) {
        int one = adapter(source, "good").fetch().postings().size();

        FetchOutcome outcome = adapter(source, "gone", "garbled", "good").fetch();

        assertThat(outcome.postings()).hasSize(one);
        assertThat(outcome.warning()).startsWith("2 of 3 " + source.label + "s could not be read.");
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void theBoardOrderDoesNotMatter(Source source) {
        int one = adapter(source, "good").fetch().postings().size();

        FetchOutcome outcome = adapter(source, "good", "broken", "good2").fetch();

        assertThat(outcome.postings()).hasSize(2 * one);
        assertThat(outcome.warning()).startsWith("1 of 3 ");
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void whenEveryBoardFailsTheSourceFails(Source source) {
        assertThatThrownBy(() -> adapter(source, "broken", "gone").fetch())
                .isInstanceOf(UpstreamException.class)
                .hasMessageStartingWith("No " + source.label + " could be read.")
                .hasMessageContaining("broken");
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void whenEveryBoardIsReadThereIsNoWarning(Source source) {
        FetchOutcome outcome = adapter(source, "good", "good2").fetch();

        assertThat(outcome.warning()).isNull();
        assertThat(outcome.postings()).isNotEmpty();
    }
}
