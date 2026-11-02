package com.vacancytracker.collection.adapter.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Vacancies the owner forwards to their own Telegram bot (T-089, ADR-045). Telegram's terms forbid scraping
 * channels, and a bot cannot read a channel it does not administer, so the tool never reads a channel: the owner
 * forwards an interesting post (or pastes a link or text) to their bot in the official app, and each run collects
 * those messages with the Bot API's getUpdates. Only messages from the owner's own chat are taken; anything else
 * sent to the bot is dropped. Telegram keeps updates for 24 hours, so runs must be at least daily.
 *
 * <p>The update offset is stored only after the vacancies are stored, so a failed run is offered the same messages
 * again (getUpdates confirms earlier updates only when it is called with a higher offset).
 */
@Component
public class TelegramForwardAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramForwardAdapter.class);
    static final String ID = "telegram-forward";
    static final int MAX_TITLE = 200;
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final Pattern COMPANY = Pattern.compile(
            "(?imu)^\\W*(?:компанія|компания|company|employer|роботодавець|работодатель)\\s*[:\\-–—]\\s*(.{2,80})$");

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final boolean enabled;
    private final String token;
    private final String ownerChatId;
    private final String baseUrl;

    public TelegramForwardAdapter(HttpFetcher fetcher, ObjectMapper mapper, JdbcClient jdbc,
                                  @Value("${app.sources.telegram-forward.enabled:false}") boolean enabled,
                                  @Value("${app.sources.telegram-forward.bot-token:${app.notifications.telegram.bot-token:}}") String token,
                                  @Value("${app.sources.telegram-forward.owner-chat-id:${app.notifications.telegram.chat-id:}}") String ownerChatId,
                                  @Value("${app.sources.telegram-forward.base-url:https://api.telegram.org}") String baseUrl) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.token = token == null ? "" : token.trim();
        this.ownerChatId = ownerChatId == null ? "" : ownerChatId.trim();
        this.baseUrl = baseUrl;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> serverSideFields() {
        return Set.of();
    }

    @Override
    public FetchOutcome fetch() {
        if (!enabled || token.isEmpty() || ownerChatId.isEmpty()) {
            return FetchOutcome.noChange();
        }
        long offset = storedOffset();
        // The token is part of the address: it is fetched without validators, so the address is never stored as a cache
        // key, and the log masking hides it (ADR-034).
        FetchResult result = fetcher.getUnconditionalOnce(baseUrl + "/bot" + token + "/getUpdates?timeout=0&limit=100"
                + "&allowed_updates=%5B%22message%22%5D" + (offset > 0 ? "&offset=" + offset : ""));
        if (!result.ok()) {
            throw new UpstreamException("Telegram responded HTTP " + result.status());
        }
        Batch batch = parse(result.body());
        if (batch.lastUpdateId() < 0) {
            return FetchOutcome.of(batch.postings());
        }
        long next = batch.lastUpdateId() + 1;
        return FetchOutcome.of(batch.postings()).afterStored(() -> storeOffset(next));
    }

    /** lastUpdateId is -1 when there were no updates. */
    record Batch(List<RawPosting> postings, long lastUpdateId) {
    }

    Batch parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            throw new UpstreamException("Telegram returned invalid JSON");
        }
        if (!root.path("ok").asBoolean(false)) {
            throw new UpstreamException("Telegram refused the request: " + root.path("description").asText("no reason given"));
        }
        List<RawPosting> postings = new ArrayList<>();
        long last = -1;
        int foreign = 0;
        for (JsonNode update : root.path("result")) {
            last = Math.max(last, update.path("update_id").asLong(-1));
            JsonNode message = update.path("message");
            if (message.isMissingNode()) {
                continue;
            }
            if (!ownerChatId.equals(message.path("chat").path("id").asText())) {
                foreign++;
                continue;
            }
            RawPosting posting = toPosting(message);
            if (posting != null) {
                postings.add(posting);
            }
        }
        if (foreign > 0) {
            LOG.info("{}: ignored {} messages that did not come from the owner's chat", ID, foreign);
        }
        return new Batch(postings, last);
    }

    private static RawPosting toPosting(JsonNode message) {
        String text = message.hasNonNull("text") ? message.path("text").asText() : message.path("caption").asText("");
        if (text.isBlank()) {
            return null;
        }
        JsonNode origin = message.path("forward_origin");
        String source = "Telegram";
        String url = null;
        String externalId = "msg:" + message.path("chat").path("id").asText() + ":" + message.path("message_id").asText();
        Instant posted = Instant.ofEpochSecond(message.path("date").asLong());
        if (!origin.isMissingNode()) {
            posted = Instant.ofEpochSecond(origin.path("date").asLong(message.path("date").asLong()));
            JsonNode chat = origin.path("chat");
            if ("channel".equals(origin.path("type").asText())) {
                source = chat.path("title").asText(source);
                externalId = "post:" + chat.path("id").asText() + ":" + origin.path("message_id").asText();
                String username = chat.path("username").asText("");
                if (!username.isEmpty()) {
                    url = "https://t.me/" + username + "/" + origin.path("message_id").asText();
                }
            } else if ("chat".equals(origin.path("type").asText())) {
                source = origin.path("sender_chat").path("title").asText(source);
            } else if ("user".equals(origin.path("type").asText())) {
                JsonNode user = origin.path("sender_user");
                source = (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).trim();
            } else if ("hidden_user".equals(origin.path("type").asText())) {
                source = origin.path("sender_user_name").asText(source);
            }
        }
        if (url == null) {
            Matcher link = LINK.matcher(text);
            if (link.find()) {
                url = link.group().replaceAll("[).,;!]+$", "");
            }
        }
        Matcher company = COMPANY.matcher(text);
        return new RawPosting(ID, externalId, url, title(text), company.find() ? company.group(1).trim() : blankTo(source, "Telegram"),
                null, null, text, null, null, null, null, posted);
    }

    /** The first line with letters, without leading emoji and markup, cut at a word. */
    static String title(String text) {
        for (String line : text.split("\\R")) {
            String clean = line.replaceAll("^[^\\p{L}\\p{N}]+", "").replaceAll("[*_`#]+", "").strip();
            if (clean.chars().anyMatch(Character::isLetter)) {
                if (clean.length() <= MAX_TITLE) {
                    return clean;
                }
                int cut = clean.lastIndexOf(' ', MAX_TITLE);
                return clean.substring(0, cut > 40 ? cut : MAX_TITLE) + "…";
            }
        }
        return "Telegram post";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long storedOffset() {
        return jdbc.sql("SELECT next_offset FROM telegram_update_offset WHERE source = :s")
                .param("s", ID).query(Long.class).optional().orElse(0L);
    }

    private void storeOffset(long next) {
        jdbc.sql("INSERT INTO telegram_update_offset (source, next_offset) VALUES (:s, :o)"
                        + " ON CONFLICT (source) DO UPDATE SET next_offset = GREATEST(telegram_update_offset.next_offset, EXCLUDED.next_offset)")
                .param("s", ID).param("o", next).update();
    }
}
