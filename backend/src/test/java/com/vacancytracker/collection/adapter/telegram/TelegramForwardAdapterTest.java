package com.vacancytracker.collection.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.support.TestHttp;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class TelegramForwardAdapterTest extends AbstractIntegrationTest {

    private static final String TOKEN = "123456:secret-bot-token";
    private static final String OWNER = "42";
    private static final String UPDATES = """
            {"ok":true,"result":[
              {"update_id":900,"message":{"message_id":1,"date":1793600000,"chat":{"id":42},
                "forward_origin":{"type":"channel","date":1793590000,"message_id":777,
                  "chat":{"id":-100123,"title":"ZAICHENKO TEAM Вакансії","username":"zaichenkoteam"}},
                "text":"🔥 Senior Java Developer (remote)\\nКомпанія: Acme Payments\\nДосвід від 5 років. Візова підтримка."}},
              {"update_id":901,"message":{"message_id":2,"date":1793600100,"chat":{"id":7},"text":"spam from a stranger"}},
              {"update_id":902,"message":{"message_id":3,"date":1793600200,"chat":{"id":42},
                "text":"QA Engineer at Beta https://jobs.example.com/qa-1."}},
              {"update_id":903,"message":{"message_id":4,"date":1793600300,"chat":{"id":42},"photo":[]}}
            ]}
            """;

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcTemplate template;

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        template.execute("TRUNCATE telegram_update_offset");
        server = TestHttp.serve(Map.of(
                "/bot" + TOKEN + "/getUpdates?timeout=0&limit=100&allowed_updates=%5B%22message%22%5D", new TestHttp.Route(200, UPDATES),
                "/bot" + TOKEN + "/getUpdates?timeout=0&limit=100&allowed_updates=%5B%22message%22%5D&offset=904",
                new TestHttp.Route(200, "{\"ok\":true,\"result\":[]}")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TelegramForwardAdapter adapter(boolean enabled, String token) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new TelegramForwardAdapter(fetcher, mapper, jdbc, enabled, token, OWNER, TestHttp.base(server));
    }

    @Test
    void takesForwardedPostsAndLinksFromTheOwnerOnly() {
        var outcome = adapter(true, TOKEN).fetch();

        assertThat(outcome.postings()).hasSize(2);
        var post = outcome.postings().get(0);
        assertThat(post.adapter()).isEqualTo("telegram-forward");
        assertThat(post.externalId()).isEqualTo("post:-100123:777");
        assertThat(post.url()).isEqualTo("https://t.me/zaichenkoteam/777");
        assertThat(post.title()).isEqualTo("Senior Java Developer (remote)");
        assertThat(post.company()).isEqualTo("Acme Payments");
        assertThat(post.postedAt()).isEqualTo(Instant.ofEpochSecond(1793590000));
        // The bot token is in the address, so nothing about the request may be kept in the HTTP cache.
        assertThat(template.queryForObject("SELECT count(*) FROM http_cache", Integer.class)).isZero();
        var pasted = outcome.postings().get(1);
        assertThat(pasted.company()).isEqualTo("Telegram");
        assertThat(pasted.url()).isEqualTo("https://jobs.example.com/qa-1");
    }

    @Test
    void theOffsetMovesOnlyAfterTheVacanciesAreStored() {
        var first = adapter(true, TOKEN).fetch();
        assertThat(template.queryForObject("SELECT count(*) FROM telegram_update_offset", Integer.class)).isZero();

        first.onStored().run();

        assertThat(template.queryForObject("SELECT next_offset FROM telegram_update_offset", Long.class)).isEqualTo(904L);
        var second = adapter(true, TOKEN).fetch();
        assertThat(second.postings()).isEmpty();
    }

    @Test
    void withoutATokenOrWhenDisabledNothingIsAsked() {
        server.stop(0);
        assertThat(adapter(false, TOKEN).fetch().unchanged()).isTrue();
        assertThat(adapter(true, "").fetch().unchanged()).isTrue();
    }

    @Test
    void aRefusalIsAFailureWithTelegramsReason() {
        assertThatThrownBy(() -> adapter(true, TOKEN).parse("{\"ok\":false,\"description\":\"Unauthorized\"}"))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("Unauthorized");
    }

    @Test
    void titlesDropEmojiAndMarkupAndAreCut() {
        assertThat(TelegramForwardAdapter.title("\n✅ **Backend Engineer**\nmore")).isEqualTo("Backend Engineer");
        assertThat(TelegramForwardAdapter.title("12345")).isEqualTo("Telegram post");
        assertThat(TelegramForwardAdapter.title("word ".repeat(80))).endsWith("…").hasSizeLessThan(TelegramForwardAdapter.MAX_TITLE + 2);
    }
}
