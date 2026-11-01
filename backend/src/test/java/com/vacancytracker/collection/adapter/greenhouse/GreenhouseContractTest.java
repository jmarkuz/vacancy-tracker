package com.vacancytracker.collection.adapter.greenhouse;

import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GreenhouseContractTest extends AdapterContractTest {

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
        healthy = serve(200, true);
        failing = serve(503, false);
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
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GreenhouseAdapter(fetcher, mapper, List.of("acme"), base);
    }

    private static HttpServer serve(int status, boolean withBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/boards/acme/jobs", ex -> {
            if (!withBody) {
                ex.sendResponseHeaders(status, -1);
                ex.close();
                return;
            }
            byte[] bytes;
            try (InputStream in = GreenhouseContractTest.class.getResourceAsStream("/fixtures/greenhouse-acme.json")) {
                bytes = in.readAllBytes();
            }
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
