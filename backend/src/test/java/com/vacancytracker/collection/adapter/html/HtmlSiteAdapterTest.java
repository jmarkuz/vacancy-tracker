package com.vacancytracker.collection.adapter.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.FetchOutcome;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HtmlSiteAdapterTest extends AdapterContractTest {

    private static final String PAGE = "<html><body><div class='j'><a class='t' href='/x'>One</a></div></body></html>";

    @Autowired
    private ConditionalCache cache;

    @Autowired
    private Clock clock;

    private HttpServer server;
    private final Map<String, TestHttp.Route> routes = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestURI().toString();
            requested.add(key);
            TestHttp.Route route = routes.getOrDefault(key, new TestHttp.Route(404, ""));
            byte[] bytes = route.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(route.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        base = TestHttp.base(server);
        routes.put("/robots.txt", new TestHttp.Route(200, "User-agent: *\nDisallow: /private\n"));
        routes.put("/jobs", new TestHttp.Route(200, fixture()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String fixture() throws IOException {
        return TestHttp.fixture(HtmlSiteAdapterTest.class, "career-page.html");
    }

    private HtmlSite site(String id, String listUrl) {
        return new HtmlSite(id, true, listUrl, 1, "2026-11-01", "https://careers.example.test/terms", "li.job", ".title", "", ".co", "", ".loc");
    }

    private SourceAdapter adapter(HtmlSite... sites) {
        HttpFetcher fetcher = new HttpFetcher(cache, new RetryPolicy(2, 1, 2, new Random(1)), new DomainRateLimiter(0, clock, ms -> { }), ms -> { });
        return new HtmlSiteAdapter(fetcher, sleeps::add, List.of(sites));
    }

    @Override
    protected SourceAdapter healthyAdapter() {
        return adapter(site("ok", base + "/jobs"));
    }

    @Override
    protected SourceAdapter failingAdapter() {
        routes.put("/jobs", new TestHttp.Route(503, ""));
        return adapter(site("broken", base + "/jobs"));
    }

    @Test
    void readsVacancyCardsWithTitleLinkCompanyAndPlace() {
        FetchOutcome outcome = adapter(site("example", base + "/jobs")).fetch();

        assertThat(outcome.postings()).hasSize(2);
        RawPosting first = outcome.postings().get(0);
        assertThat(first.adapter()).isEqualTo("html");
        assertThat(first.title()).isEqualTo("Example Role Alpha");
        assertThat(first.url()).isEqualTo(base + "/jobs/101");
        assertThat(first.company()).isEqualTo("Northwind Labs");
        assertThat(first.location()).isEqualTo("Prague");
        assertThat(first.externalId()).startsWith("example:");
        assertThat(outcome.postings().get(1).url()).isEqualTo("https://careers.example.test/jobs/102?utm=x");
        assertThat(outcome.postings()).extracting(RawPosting::externalId).doesNotHaveDuplicates();
    }

    @Test
    void cardsWithoutATitleOrARealLinkAreSkippedAndDuplicatesCollapse() {
        FetchOutcome outcome = adapter(site("example", base + "/jobs")).fetch();

        assertThat(outcome.postings()).extracting(RawPosting::title).containsExactly("Example Role Alpha", "Senior Example Role");
    }

    @Test
    void aFixedCompanyNameIsUsedWhenThereIsNoCompanySelector() {
        HtmlSite own = new HtmlSite("own", true, base + "/jobs", 1, "2026-11-01", "https://x.test/terms", "li.job", ".title", "", "", "Acme", "");

        assertThat(adapter(own).fetch().postings()).extracting(RawPosting::company).containsOnly("Acme");
    }

    @Test
    void nothingIsRequestedForASiteWithoutARecordedTermsCheckOrThatIsDisabled() {
        HtmlSite noTerms = new HtmlSite("a", true, base + "/jobs", 1, "", "https://x.test/terms", "li.job", ".title", "", "", "", "");
        HtmlSite noTermsUrl = new HtmlSite("b", true, base + "/jobs", 1, "2026-11-01", " ", "li.job", ".title", "", "", "", "");
        HtmlSite disabled = new HtmlSite("c", false, base + "/jobs", 1, "2026-11-01", "https://x.test/terms", "li.job", ".title", "", "", "", "");
        HtmlSite noSelector = new HtmlSite("d", true, base + "/jobs", 1, "2026-11-01", "https://x.test/terms", "", ".title", "", "", "", "");
        HtmlSite badUrl = new HtmlSite("e", true, "ftp://x.test/jobs", 1, "2026-11-01", "https://x.test/terms", "li.job", ".title", "", "", "", "");

        FetchOutcome outcome = adapter(noTerms, noTermsUrl, disabled, noSelector, badUrl).fetch();

        assertThat(outcome.postings()).isEmpty();
        assertThat(requested).isEmpty();
    }

    @Test
    void noSitesConfiguredMeansNothingHappens() {
        assertThat(adapter().fetch().postings()).isEmpty();
        assertThat(requested).isEmpty();
        assertThat(new HtmlSourcesProperties(null).sites()).isNull();
    }

    @Test
    void robotsTxtIsReadFirstAndADisallowedPageIsNeverRequested() {
        routes.put("/robots.txt", new TestHttp.Route(200, "User-agent: *\nDisallow: /jobs\n"));

        FetchOutcome outcome = adapter(site("blocked", base + "/jobs")).fetch();

        assertThat(outcome.postings()).isEmpty();
        assertThat(requested).containsExactly("/robots.txt");
    }

    @Test
    void ourOwnRobotsGroupOverridesTheStarGroup() {
        routes.put("/robots.txt", new TestHttp.Route(200, "User-agent: *\nDisallow: /\n\nUser-agent: VacancyTracker\nAllow: /jobs\n"));

        assertThat(adapter(site("allowed", base + "/jobs")).fetch().postings()).hasSize(2);
    }

    @Test
    void aMissingRobotsTxtAllowsAndAnUnreadableOneBlocks() {
        routes.remove("/robots.txt");
        assertThat(adapter(site("none", base + "/jobs")).fetch().postings()).hasSize(2);

        requested.clear();
        routes.put("/robots.txt", new TestHttp.Route(500, "oops"));
        assertThatThrownBy(() -> adapter(site("flaky", base + "/jobs")).fetch()).isInstanceOf(UpstreamException.class);
        assertThat(requested).doesNotContain("/jobs");
    }

    @Test
    void anUnreachableRobotsTxtBlocksTheSite() throws IOException {
        server.stop(0);

        assertThatThrownBy(() -> adapter(site("down", base + "/jobs")).fetch()).isInstanceOf(UpstreamException.class);
    }

    @Test
    void thePageQueryIsCheckedAgainstRobotsToo() {
        routes.put("/robots.txt", new TestHttp.Route(200, "User-agent: *\nDisallow: /jobs?*page=2\n"));
        routes.put("/jobs?page=1", new TestHttp.Route(200, PAGE));
        routes.put("/jobs?page=2", new TestHttp.Route(200, PAGE.replace("/x", "/y")));
        HtmlSite paged = new HtmlSite("paged", true, base + "/jobs?page={page}", 3, "2026-11-01", "https://x.test/terms", "div.j", "a.t", "", "", "", "");

        FetchOutcome outcome = adapter(paged).fetch();

        assertThat(outcome).isNotNull();
        assertThat(requested).contains("/jobs?page=1").doesNotContain("/jobs?page=2");
    }

    @Test
    void severalPagesAreReadUpToTheLimitAndStopWhenAPageAddsNothing() {
        routes.put("/jobs?page=1", new TestHttp.Route(200, PAGE));
        routes.put("/jobs?page=2", new TestHttp.Route(200, PAGE.replace("/x", "/y")));
        routes.put("/jobs?page=3", new TestHttp.Route(200, PAGE.replace("/x", "/y")));
        routes.put("/jobs?page=4", new TestHttp.Route(200, PAGE.replace("/x", "/z")));
        HtmlSite paged = new HtmlSite("paged", true, base + "/jobs?page={page}", 4, "2026-11-01", "https://x.test/terms", "div.j", "a.t", "", "", "", "");

        FetchOutcome outcome = adapter(paged).fetch();

        assertThat(outcome.postings()).hasSize(2);
        assertThat(requested).doesNotContain("/jobs?page=4");
    }

    @Test
    void aCrawlDelayInRobotsTxtIsWaitedBetweenPages() {
        routes.put("/robots.txt", new TestHttp.Route(200, "User-agent: *\nCrawl-delay: 7\n"));
        routes.put("/jobs?page=1", new TestHttp.Route(200, PAGE));
        routes.put("/jobs?page=2", new TestHttp.Route(200, PAGE.replace("/x", "/y")));
        routes.put("/jobs?page=3", new TestHttp.Route(200, "<html></html>"));
        HtmlSite paged = new HtmlSite("paged", true, base + "/jobs?page={page}", 3, "2026-11-01", "https://x.test/terms", "div.j", "a.t", "", "", "", "");

        adapter(paged).fetch();

        assertThat(sleeps).containsExactly(7000L, 7000L);
    }

    @Test
    void oneFailingSiteDoesNotStopTheOthersButAllFailingRaises() {
        routes.put("/broken", new TestHttp.Route(503, ""));

        FetchOutcome mixed = adapter(site("broken", base + "/broken"), site("fine", base + "/jobs")).fetch();
        assertThat(mixed.postings()).hasSize(2);

        assertThatThrownBy(() -> adapter(site("broken", base + "/broken")).fetch())
                .isInstanceOf(UpstreamException.class).hasMessageContaining("broken");
    }

    @Test
    void anInvalidSelectorIsReportedAsASettingsProblemNotACrash() {
        HtmlSite bad = new HtmlSite("bad", true, base + "/jobs", 1, "2026-11-01", "https://x.test/terms", "li[", ".title", "", "", "", "");

        assertThatThrownBy(() -> adapter(bad).fetch()).isInstanceOf(UpstreamException.class).hasMessageContaining("invalid selector");
    }

    @Test
    void theUserAgentNamesTheToolSoRobotsRulesCanTargetIt() {
        assertThat(HttpFetcher.PRODUCT_TOKEN).isEqualTo("VacancyTracker");
    }
}
