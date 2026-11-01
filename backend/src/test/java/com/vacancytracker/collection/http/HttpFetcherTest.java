package com.vacancytracker.collection.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HttpFetcherTest extends AbstractIntegrationTest {

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private Clock clock;

    private HttpServer server;
    private String base;
    private final AtomicInteger hits = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> ifNoneMatch = new ConcurrentLinkedQueue<>();
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        hits.set(0);
        ifNoneMatch.clear();
        sleeps.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", ex -> {
            hits.incrementAndGet();
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (inm != null) {
                ifNoneMatch.add(inm);
            }
            if ("\"v1\"".equals(inm)) {
                ex.sendResponseHeaders(304, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("ETag", "\"v1\"");
            send(ex, 200, "{\"ok\":true}");
        });
        server.createContext("/flaky", ex -> {
            if (hits.incrementAndGet() == 1) {
                ex.getResponseHeaders().add("Retry-After", "2");
                send(ex, 429, "slow down");
            } else {
                send(ex, 200, "fine");
            }
        });
        server.createContext("/down", ex -> {
            hits.incrementAndGet();
            send(ex, 500, "boom");
        });
        server.createContext("/missing", ex -> {
            hits.incrementAndGet();
            send(ex, 404, "nope");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpFetcher fetcher() {
        return new HttpFetcher(cache, new RetryPolicy(4, 10, 20, new Random(3)),
                new DomainRateLimiter(0, clock, sleeps::add), sleeps::add);
    }

    @Test
    void secondRequestSendsValidatorsAndGetsNotModified() {
        HttpFetcher fetcher = fetcher();
        FetchResult first = fetcher.get(base + "/feed");
        FetchResult second = fetcher.get(base + "/feed");

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.etag()).isEqualTo("\"v1\"");
        assertThat(second.notModified()).isTrue();
        assertThat(ifNoneMatch).containsExactly("\"v1\"");
    }

    @Test
    void rateLimitedResponseIsRetriedAndRetryAfterIsHonored() {
        FetchResult result = fetcher().get(base + "/flaky");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("fine");
        assertThat(hits.get()).isEqualTo(2);
        assertThat(sleeps).contains(2000L);
    }

    @Test
    void persistentServerErrorGivesUpAfterMaxAttempts() {
        assertThatThrownBy(() -> fetcher().get(base + "/down"))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("HTTP 500 after 4 attempts");
        assertThat(hits.get()).isEqualTo(4);
    }

    @Test
    void clientErrorIsReturnedWithoutRetry() {
        FetchResult result = fetcher().get(base + "/missing");

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.ok()).isFalse();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void onlyHttpAndHttpsAreAllowed() {
        assertThatThrownBy(() -> fetcher().get("file:///etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
