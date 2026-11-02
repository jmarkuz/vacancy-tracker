package com.vacancytracker.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.pipeline.RunEventBus;
import com.vacancytracker.notification.TelegramDigestService.DigestResult;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class TelegramDigestServiceTest extends AbstractIntegrationTest {

    private static final String TOKEN = "123456:SECRET-bot-token";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcTemplate template;

    @Autowired
    private ObjectMapper mapper;

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws IOException {
        template.execute("TRUNCATE search_profile CASCADE");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            bodies.add(mapper.readTree(ex.getRequestBody().readAllBytes()));
            String reply = status.get() == 200 ? "{\"ok\":true}" : "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TelegramDigestService service(boolean enabled, String token, String chat, int max, RunEventBus bus) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new TelegramDigestService(jdbc, mapper, bus, Clock.systemUTC(), enabled, token, chat, base, max, 3);
    }

    private TelegramDigestService service() {
        return service(true, TOKEN, "42", 10, new RunEventBus());
    }

    private UUID profile(String name, int notifyMin, boolean enabled) {
        UUID id = UUID.randomUUID();
        template.update("INSERT INTO search_profile (id, name, enabled, sources, thresholds, version) VALUES (?, ?, ?, '{all}', CAST(? AS JSONB), 1)",
                id, name, enabled, "{\"inboxMinScore\":0,\"notifyMinScore\":" + notifyMin + ",\"maxAgeDays\":60}");
        return id;
    }

    private UUID vacancy(String title, String status, Instant firstSeen) {
        UUID id = UUID.randomUUID();
        template.update("INSERT INTO vacancy (id, title, company, location, url, status, first_seen_at, last_seen_at) VALUES (?, ?, 'Acme', 'Prague', ?, ?, ?, ?)",
                id, title, "https://ex.com/" + id, status, Timestamp.from(firstSeen), Timestamp.from(firstSeen));
        return id;
    }

    private UUID vacancy(String title) {
        return vacancy(title, "New", Instant.now());
    }

    private void match(UUID vacancy, UUID profile, Integer score, boolean hidden) {
        template.update("INSERT INTO vacancy_match (vacancy_id, profile_id, profile_version, score, hidden, matched_at) VALUES (?, ?, 1, ?, ?, now())",
                vacancy, profile, score, hidden);
    }

    private String lastText() {
        return bodies.get(bodies.size() - 1).get("text").asText();
    }

    @Test
    void sendsOnlyMatchesAtOrAboveTheirProfilesNotifyThresholdAndOnlyOnce() {
        UUID p = profile("P", 70, true);
        UUID high = vacancy("High match");
        UUID edge = vacancy("Edge match");
        UUID low = vacancy("Low match");
        match(high, p, 90, false);
        match(edge, p, 70, false);
        match(low, p, 69, false);

        DigestResult first = service().sendDigest();

        assertThat(first).extracting(DigestResult::enabled, DigestResult::sent, DigestResult::waiting, DigestResult::error).containsExactly(true, 2, 0, null);
        assertThat(paths).containsExactly("/bot" + TOKEN + "/sendMessage");
        assertThat(bodies.get(0).get("chat_id").asText()).isEqualTo("42");
        assertThat(lastText()).startsWith("2 new matches").contains("90 · High match — Acme (Prague)").contains("70 · Edge match").doesNotContain("Low match");
        assertThat(lastText()).contains("https://ex.com/" + high);
        assertThat(lastText().indexOf("High match")).isLessThan(lastText().indexOf("Edge match"));

        DigestResult second = service().sendDigest();

        assertThat(second.sent()).isZero();
        assertThat(paths).hasSize(1);
    }

    @Test
    void eachProfileUsesItsOwnThresholdAndTheBestQualifyingScoreCounts() {
        UUID strict = profile("Strict", 90, true);
        UUID relaxed = profile("Relaxed", 50, true);
        UUID v = vacancy("Both");
        match(v, strict, 80, false);
        match(v, relaxed, 60, false);
        UUID other = vacancy("Only strict");
        match(other, strict, 85, false);

        service().sendDigest();

        assertThat(lastText()).contains("60 · Both").doesNotContain("Only strict");
    }

    @Test
    void hiddenFilteredNonNewAndDisabledProfileMatchesAreNotSent() {
        UUID p = profile("P", 10, true);
        UUID off = profile("Off", 10, false);
        match(vacancy("Filtered"), p, null, true);
        UUID saved = vacancy("Already saved", "Saved", Instant.now());
        match(saved, p, 99, false);
        UUID viaOff = vacancy("Via disabled profile");
        match(viaOff, off, 99, false);
        UUID ok = vacancy("Fine");
        match(ok, p, 50, false);

        service().sendDigest();

        assertThat(lastText()).contains("1 new match").contains("Fine").doesNotContain("Filtered").doesNotContain("Already saved").doesNotContain("disabled");
    }

    @Test
    void oldVacanciesAreNotDugUp() {
        UUID p = profile("P", 10, true);
        UUID old = vacancy("Ancient", "New", Instant.now().minus(30, ChronoUnit.DAYS));
        match(old, p, 99, false);

        DigestResult result = service().sendDigest();

        assertThat(result.sent()).isZero();
        assertThat(paths).isEmpty();
    }

    @Test
    void nothingIsSentOrRememberedWhenDisabledOrNotFullyConfigured() {
        UUID p = profile("P", 10, true);
        match(vacancy("Waiting"), p, 99, false);

        assertThat(service(false, TOKEN, "42", 10, new RunEventBus()).sendDigest().enabled()).isFalse();
        assertThat(service(true, "", "42", 10, new RunEventBus()).sendDigest().enabled()).isFalse();
        assertThat(service(true, TOKEN, " ", 10, new RunEventBus()).sendDigest().enabled()).isFalse();

        assertThat(paths).isEmpty();
        assertThat(template.queryForObject("SELECT count(*) FROM notified_vacancy", Integer.class)).isZero();
    }

    @Test
    void aRejectedMessageIsNotRememberedSoItIsOfferedAgain() {
        UUID p = profile("P", 10, true);
        match(vacancy("Retry me"), p, 99, false);
        status.set(400);

        DigestResult failed = service().sendDigest();

        assertThat(failed.sent()).isZero();
        assertThat(failed.error()).contains("HTTP 400").contains("chat not found").doesNotContain(TOKEN);
        assertThat(template.queryForObject("SELECT count(*) FROM notified_vacancy", Integer.class)).isZero();

        status.set(200);
        assertThat(service().sendDigest().sent()).isEqualTo(1);
    }

    @Test
    void anUnreachableServerIsReportedWithoutTheTokenAndWithoutThrowing() {
        UUID p = profile("P", 10, true);
        match(vacancy("Nobody home"), p, 99, false);
        server.stop(0);

        DigestResult result = service().sendDigest();

        assertThat(result.error()).startsWith("Could not reach Telegram").doesNotContain(TOKEN).doesNotContain("SECRET");
        assertThat(template.queryForObject("SELECT count(*) FROM notified_vacancy", Integer.class)).isZero();
    }

    @Test
    void moreThanTheLimitIsSplitAcrossDigests() {
        UUID p = profile("P", 10, true);
        for (int i = 0; i < 5; i++) {
            match(vacancy("Job " + i), p, 90 - i, false);
        }
        TelegramDigestService service = service(true, TOKEN, "42", 3, new RunEventBus());

        DigestResult first = service.sendDigest();
        assertThat(first.sent()).isEqualTo(3);
        assertThat(first.waiting()).isEqualTo(2);
        assertThat(lastText()).contains("…and 2 more in the app");

        DigestResult second = service.sendDigest();
        assertThat(second.sent()).isEqualTo(2);
        assertThat(lastText()).contains("Job 3").contains("Job 4").doesNotContain("Job 0");
    }

    @Test
    void aLongDigestIsKeptUnderTelegramsLimit() {
        UUID p = profile("P", 10, true);
        for (int i = 0; i < 10; i++) {
            match(vacancy("T".repeat(600) + i), p, 90, false);
        }

        DigestResult result = service().sendDigest();

        assertThat(lastText().length()).isLessThanOrEqualTo(TelegramDigestService.TELEGRAM_LIMIT);
        assertThat(result.sent() + result.waiting()).isEqualTo(10);
        assertThat(result.sent()).isLessThan(10);
    }

    @Test
    void titlesAreSentAsPlainTextOnOneLine() {
        UUID p = profile("P", 10, true);
        match(vacancy("<b>Bold</b>\n  [x](http://evil)"), p, 90, false);

        service().sendDigest();

        assertThat(bodies.get(0).has("parse_mode")).isFalse();
        assertThat(lastText()).contains("<b>Bold</b> [x](http://evil) — Acme");
    }

    @Test
    void aFinishedRunTriggersTheDigestOnlyWhenEnabled() throws InterruptedException {
        UUID p = profile("P", 10, true);
        match(vacancy("After the run"), p, 90, false);
        RunEventBus bus = new RunEventBus();
        service(false, TOKEN, "42", 10, bus).listenForRuns();
        bus.publish(UUID.randomUUID(), "done", Map.of());

        TelegramDigestService enabled = service(true, TOKEN, "42", 10, bus);
        enabled.listenForRuns();
        // Events come in order: the disabled service's "done" and this "source" were handled before the next "done"
        // is, so exactly one message after it shows that neither of them sent anything.
        bus.publish(UUID.randomUUID(), "source", Map.of());
        bus.publish(UUID.randomUUID(), "done", Map.of());
        for (int i = 0; i < 50 && paths.isEmpty(); i++) {
            Thread.sleep(100);
        }

        assertThat(paths).hasSize(1);
        enabled.stop();
    }
}
