package com.vacancytracker.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vacancytracker.collection.pipeline.RunEventBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Telegram digest (T-067, ADR-031). Off unless enabled with a bot token and a chat id. After a collection run it
 * sends one message with the new matches whose score reaches the notifyMinScore of the profile that scored them,
 * and remembers them so each vacancy is announced once. Only title, company, place, score and link are sent.
 * The token is never logged. A failed send changes nothing, so the matches are offered again after the next run.
 */
@Service
public class TelegramDigestService {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramDigestService.class);
    static final String CHANNEL = "telegram";
    static final int TELEGRAM_LIMIT = 4096;

    /** score is the best score among the profiles that reach their own notify threshold. */
    record Candidate(UUID id, String title, String company, String location, String url, int score) {
    }

    /** sent is how many vacancies the message announced; waiting is how many more qualified but did not fit. */
    public record DigestResult(boolean enabled, int sent, int waiting, String error) {
    }

    private static final String CANDIDATES = """
            SELECT v.id, v.title, v.company, v.location, v.url, max(m.score) AS score
            FROM vacancy v
            JOIN vacancy_match m ON m.vacancy_id = v.id
            JOIN search_profile p ON p.id = m.profile_id
            WHERE v.status = 'New' AND p.enabled AND NOT m.hidden
              AND m.score >= (p.thresholds ->> 'notifyMinScore')::int
              AND v.first_seen_at >= :since
              AND NOT EXISTS (SELECT 1 FROM notified_vacancy n WHERE n.vacancy_id = v.id AND n.channel = :channel)
            GROUP BY v.id
            ORDER BY max(m.score) DESC, v.last_seen_at DESC, v.id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final RunEventBus runEvents;
    private final Clock clock;
    private final boolean enabled;
    private final String token;
    private final String chatId;
    private final String baseUrl;
    private final int maxItems;
    private final int maxAgeDays;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-digest");
        t.setDaemon(true);
        return t;
    });

    public TelegramDigestService(JdbcClient jdbc, ObjectMapper mapper, RunEventBus runEvents, Clock clock,
                                 @Value("${app.notifications.telegram.enabled:false}") boolean enabled,
                                 @Value("${app.notifications.telegram.bot-token:}") String token,
                                 @Value("${app.notifications.telegram.chat-id:}") String chatId,
                                 @Value("${app.notifications.telegram.base-url:https://api.telegram.org}") String baseUrl,
                                 @Value("${app.notifications.telegram.max-items:10}") int maxItems,
                                 @Value("${app.notifications.telegram.max-age-days:3}") int maxAgeDays) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.runEvents = runEvents;
        this.clock = clock;
        this.enabled = enabled;
        this.token = token;
        this.chatId = chatId;
        this.baseUrl = baseUrl;
        this.maxItems = Math.max(1, maxItems);
        this.maxAgeDays = Math.max(1, maxAgeDays);
    }

    public boolean isEnabled() {
        return enabled && !token.isBlank() && !chatId.isBlank();
    }

    @PostConstruct
    void listenForRuns() {
        if (isEnabled()) {
            runEvents.addDoneListener(runId -> executor.execute(this::sendDigest));
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** Sends the digest now. Does nothing when disabled. Never throws. */
    public synchronized DigestResult sendDigest() {
        if (!isEnabled()) {
            return new DigestResult(false, 0, 0, null);
        }
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(maxAgeDays)));
        List<Candidate> candidates = jdbc.sql(CANDIDATES).param("since", since).param("channel", CHANNEL)
                .query((rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("company"),
                        rs.getString("location"), rs.getString("url"), rs.getInt("score")))
                .list();
        if (candidates.isEmpty()) {
            return new DigestResult(true, 0, 0, null);
        }
        List<Candidate> included = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), maxItems)));
        String text = message(included, candidates.size() - included.size());
        while (text.length() > TELEGRAM_LIMIT && included.size() > 1) {
            included.remove(included.size() - 1);
            text = message(included, candidates.size() - included.size());
        }
        String error = post(text.length() > TELEGRAM_LIMIT ? text.substring(0, TELEGRAM_LIMIT) : text);
        if (error != null) {
            return new DigestResult(true, 0, candidates.size(), error);
        }
        for (Candidate c : included) {
            jdbc.sql("INSERT INTO notified_vacancy (vacancy_id, channel) VALUES (:id, :channel) ON CONFLICT DO NOTHING")
                    .param("id", c.id()).param("channel", CHANNEL).update();
        }
        return new DigestResult(true, included.size(), candidates.size() - included.size(), null);
    }

    static String message(List<Candidate> items, int more) {
        StringBuilder out = new StringBuilder(items.size() == 1 ? "1 new match" : items.size() + " new matches");
        for (Candidate c : items) {
            out.append("\n\n").append(c.score()).append(" · ").append(oneLine(c.title())).append(" — ").append(oneLine(c.company()));
            if (c.location() != null && !c.location().isBlank()) {
                out.append(" (").append(oneLine(c.location())).append(')');
            }
            if (c.url() != null && !c.url().isBlank()) {
                out.append('\n').append(c.url().trim());
            }
        }
        if (more > 0) {
            out.append("\n\n…and ").append(more).append(" more in the app");
        }
        return out.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Returns an error text without the token, or null when Telegram accepted the message. */
    String post(String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("disable_web_page_preview", true);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body().isBlank() ? "{}" : response.body());
            if (response.statusCode() / 100 == 2 && json.path("ok").asBoolean(false)) {
                return null;
            }
            String reason = "Telegram answered HTTP " + response.statusCode()
                    + (json.hasNonNull("description") ? ": " + json.get("description").asText() : "");
            LOG.warn("Telegram digest not sent. {}", reason);
            return reason;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        } catch (IOException | IllegalArgumentException e) {
            // Only the exception type: its text can contain the request address, and the address contains the token.
            LOG.warn("Telegram digest not sent: {}", e.getClass().getSimpleName());
            return "Could not reach Telegram (" + e.getClass().getSimpleName() + ")";
        }
    }
}
