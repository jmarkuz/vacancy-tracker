package com.vacancytracker.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A local HTTP server for adapter tests. Routes match path plus query, for example "/x?page=2". */
public final class TestHttp {

    public record Route(int status, String body, String etag) {

        public Route(int status, String body) {
            this(status, body, null);
        }
    }

    private TestHttp() {
    }

    public static HttpServer serve(Map<String, Route> routes) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            String key = ex.getRequestURI().toString();
            Route route = routes.getOrDefault(key, new Route(404, ""));
            byte[] bytes = route.body().getBytes(StandardCharsets.UTF_8);
            if (route.etag() != null) {
                if (route.etag().equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                    ex.sendResponseHeaders(304, -1);
                    ex.close();
                    return;
                }
                ex.getResponseHeaders().add("ETag", route.etag());
            }
            ex.sendResponseHeaders(route.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                ex.close();
            }
        });
        server.start();
        return server;
    }

    public static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static String fixture(Class<?> owner, String name) throws IOException {
        try (InputStream in = owner.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
