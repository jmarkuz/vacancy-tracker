package com.vacancytracker.collection.adapter.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.ConditionalCache;
import com.vacancytracker.collection.http.DomainRateLimiter;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.RetryPolicy;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.support.TestHttp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RssAdapterTest extends AdapterContractTest {

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private Clock clock;

    private HttpServer server;
    private final Map<String, TestHttp.Route> routes = new ConcurrentHashMap<>();
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            TestHttp.Route route = routes.getOrDefault(ex.getRequestURI().toString(), new TestHttp.Route(404, ""));
            byte[] bytes = route.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(route.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        base = TestHttp.base(server);
        routes.put("/rss", new TestHttp.Route(200, TestHttp.fixture(RssAdapterTest.class, "jobs-rss.xml")));
        routes.put("/atom", new TestHttp.Route(200, TestHttp.fixture(RssAdapterTest.class, "jobs-atom.xml")));
        routes.put("/evil", new TestHttp.Route(200, TestHttp.fixture(RssAdapterTest.class, "doctype.xml")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RssFeed feed(String id, String path) {
        return new RssFeed(id, true, base + path, "2026-11-02", "https://feeds.example.test/terms", "");
    }

    private SourceAdapter adapter(RssFeed... feeds) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)),
                new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new RssAdapter(fetcher, new RssSourcesProperties(List.of(feeds)));
    }

    @Override
    protected SourceAdapter healthyAdapter() {
        return adapter(feed("ok", "/rss"));
    }

    @Override
    protected SourceAdapter failingAdapter() {
        routes.put("/rss", new TestHttp.Route(503, ""));
        return adapter(feed("broken", "/rss"));
    }

    @Test
    void readsRssItemsAndSanitizesTheHtmlInTheDescription() {
        List<RawPosting> postings = adapter(feed("example", "/rss")).fetch().postings();

        assertThat(postings).extracting(RawPosting::title).containsExactly("Example Role Alpha", "Example Role Beta");
        RawPosting first = postings.get(0);
        assertThat(first.adapter()).isEqualTo("rss");
        assertThat(first.externalId()).isEqualTo("example:job-1");
        assertThat(first.url()).isEqualTo("https://feeds.example.test/jobs/1");
        assertThat(first.company()).isEqualTo("Northwind Labs");
        assertThat(first.postedAt()).isEqualTo(java.time.Instant.parse("2026-10-26T09:00:00Z"));
        assertThat(first.description()).contains("Build things & ship them.").contains("Remote")
                .doesNotContain("<").doesNotContain("alert");
        assertThat(postings.get(1).company()).as("no author falls back to the feed id").isEqualTo("example");
    }

    @Test
    void readsAtomEntries() {
        List<RawPosting> postings = adapter(feed("atom", "/atom")).fetch().postings();

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).externalId()).isEqualTo("atom:urn:example:entry:7");
        assertThat(postings.get(0).company()).isEqualTo("Contoso");
        assertThat(postings.get(0).description()).isEqualTo("Atom body");
        assertThat(postings.get(0).postedAt()).isEqualTo(java.time.Instant.parse("2026-10-25T09:00:00Z"));
    }

    @Test
    void aFixedCompanyNameWins() {
        RssFeed own = new RssFeed("own", true, base + "/rss", "2026-11-02", "https://x.test/terms", "Acme");

        assertThat(adapter(own).fetch().postings()).extracting(RawPosting::company).containsOnly("Acme");
    }

    @Test
    void aFeedWithoutARecordedTermsCheckIsNeverRequested() {
        RssFeed unchecked = new RssFeed("raw", true, base + "/rss", "", "", "");
        RssFeed disabled = new RssFeed("off", false, base + "/rss", "2026-11-02", "https://x.test/terms", "");

        assertThat(adapter(unchecked, disabled).fetch().postings()).isEmpty();
    }

    @Test
    void aBrokenFeedDoesNotStopTheOthersButAllBrokenFails() {
        routes.put("/bad", new TestHttp.Route(200, "<html>not a feed</html>"));

        assertThat(adapter(feed("bad", "/bad"), feed("ok", "/rss")).fetch().postings()).hasSize(2);
        assertThatThrownBy(() -> adapter(feed("bad", "/bad")).fetch())
                .isInstanceOf(UpstreamException.class).hasMessageContaining("RSS feed bad: the feed is not valid");
    }

    @Test
    void aDoctypeIsRefusedAndNoLocalFileIsRead() {
        assertThatThrownBy(() -> adapter(feed("evil", "/evil")).fetch())
                .isInstanceOf(UpstreamException.class).hasMessageContaining("not valid");
    }
}
