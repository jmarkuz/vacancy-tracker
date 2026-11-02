package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ImportUrlTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private HttpServer server;
    private String base;

    @BeforeEach
    void startSource() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jobs/ok", exchange -> respond(exchange, 200, fixtureBytes("job-jsonld.html")));
        server.createContext("/jobs/plain", exchange -> respond(exchange, 200, "<html>no data</html>".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/jobs/down", exchange -> respond(exchange, 503, new byte[0]));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopSource() {
        server.stop(0);
    }

    @Test
    void importsVacancyFromJsonLd() throws Exception {
        mvc.perform(post("/api/vacancies/import-url").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + base + "/jobs/ok?utm_source=feed\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.title").value("Backend Engineer (Java)"))
                .andExpect(jsonPath("$.company").value("Northwind Labs"))
                .andExpect(jsonPath("$.location").value("Berlin, DE"))
                .andExpect(jsonPath("$.workMode").value("remote"))
                .andExpect(jsonPath("$.salaryMin").value(70000))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.status").value("New"))
                .andExpect(jsonPath("$.canonicalUrl").value(base + "/jobs/ok"));
    }

    @Test
    void pageWithoutJobDataIsUnprocessable() throws Exception {
        mvc.perform(post("/api/vacancies/import-url").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + base + "/jobs/plain\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sourceErrorIsBadGateway() throws Exception {
        mvc.perform(post("/api/vacancies/import-url").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + base + "/jobs/down\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void nonHttpUrlIsRejected() throws Exception {
        mvc.perform(post("/api/vacancies/import-url").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"file:///etc/passwd\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream in = ImportUrlTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
