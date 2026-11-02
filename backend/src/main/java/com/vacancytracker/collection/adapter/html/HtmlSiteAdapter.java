package com.vacancytracker.collection.adapter.html;

import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.Sleeper;
import com.vacancytracker.collection.http.UpstreamException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Generic HTML adapter for career pages (T-073, ADR-035). It reads only sites that are enabled in the configuration
 * AND carry a recorded terms check, and only pages that robots.txt allows for this tool. A site that fails the
 * checks is skipped with a log line and never requested. Vacancies are read from the list page with CSS selectors
 * from the configuration; no vacancy page is opened. The repository ships no site.
 */
@Component
@EnableConfigurationProperties(HtmlSourcesProperties.class)
public class HtmlSiteAdapter implements SourceAdapter {

    public static final String ID = "html";
    static final int MAX_ITEMS_PER_PAGE = 200;
    static final int MAX_CRAWL_DELAY_SECONDS = 30;
    private static final Logger LOG = LoggerFactory.getLogger(HtmlSiteAdapter.class);

    private final HttpFetcher fetcher;
    private final Sleeper sleeper;
    private final List<HtmlSite> sites;

    @Autowired
    public HtmlSiteAdapter(HttpFetcher fetcher, HtmlSourcesProperties properties) {
        this(fetcher, Sleeper.THREAD, properties.sites());
    }

    HtmlSiteAdapter(HttpFetcher fetcher, Sleeper sleeper, List<HtmlSite> sites) {
        this.fetcher = fetcher;
        this.sleeper = sleeper;
        this.sites = sites == null ? List.of() : sites;
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
        List<RawPosting> postings = new ArrayList<>();
        int read = 0;
        int failed = 0;
        String firstError = null;
        for (HtmlSite site : sites) {
            if (!site.approved()) {
                if (site.enabled()) {
                    LOG.warn("HTML site '{}' is enabled but has no recorded terms check or is incomplete; it is not read", site.id());
                }
                continue;
            }
            try {
                postings.addAll(readSite(site));
                read++;
            } catch (UpstreamException e) {
                failed++;
                String reason = "HTML site " + site.id() + ": " + e.getMessage();
                firstError = firstError == null ? reason : firstError;
                LOG.warn("{}", reason);
            }
        }
        if (read == 0 && failed > 0) {
            throw new UpstreamException("No HTML site could be read. " + firstError);
        }
        FetchOutcome outcome = FetchOutcome.of(postings);
        return failed > 0 ? outcome.withWarning(failed + " of " + (read + failed) + " sites could not be read. " + firstError) : outcome;
    }

    private List<RawPosting> readSite(HtmlSite site) {
        URI first = URI.create(site.listUrl().replace("{page}", "1"));
        String origin = first.getScheme() + "://" + first.getAuthority();
        RobotsRules robots = robots(origin);
        Map<String, RawPosting> found = new LinkedHashMap<>();
        for (int page = 1; page <= site.pages(); page++) {
            URI url = URI.create(site.listUrl().replace("{page}", String.valueOf(page)));
            if (!robots.isAllowed(pathAndQuery(url))) {
                // Not read, so its vacancies are not known to be gone: reported as a failure of this site.
                throw new UpstreamException("HTML site " + site.id() + ": robots.txt does not allow reading " + url.getPath());
            }
            if (page > 1 && robots.crawlDelaySeconds() > 0) {
                sleeper.sleep(Math.min(robots.crawlDelaySeconds(), MAX_CRAWL_DELAY_SECONDS) * 1000L);
            }
            FetchResult result = fetcher.getUnconditionalWithoutRedirects(url.toString());
            if (!result.ok()) {
                throw new UpstreamException("HTML site " + site.id() + " responded HTTP " + result.status());
            }
            int before = found.size();
            parse(site, result.body(), url.toString(), found);
            if (found.size() == before) {
                break; // an empty or repeated page: there is nothing further to read
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * 2xx: the rules. 4xx: no rules, everything allowed (RFC 9309). Anything else, or no answer: the permission
     * cannot be checked, so nothing is fetched and the site counts as failed.
     */
    private RobotsRules robots(String origin) {
        FetchResult result;
        try {
            result = fetcher.getUnconditional(origin + "/robots.txt");
        } catch (UpstreamException e) {
            throw new UpstreamException("robots.txt could not be read (" + e.getMessage() + "); the site is not fetched");
        }
        if (result.status() / 100 == 2) {
            return RobotsRules.parse(result.body(), HttpFetcher.PRODUCT_TOKEN);
        }
        if (result.status() / 100 == 4) {
            return RobotsRules.allowAll();
        }
        throw new UpstreamException("robots.txt answered HTTP " + result.status() + "; the site is not fetched");
    }

    private void parse(HtmlSite site, String html, String pageUrl, Map<String, RawPosting> into) {
        Document doc = Jsoup.parse(html, pageUrl);
        List<Element> cards;
        try {
            cards = doc.select(site.item());
            // The other selectors are checked once, so a bad one is reported as such and not as a crash per card.
            new Element("div").select(site.title());
            for (String selector : new String[] {site.link(), site.company(), site.location()}) {
                if (!selector.isBlank()) {
                    new Element("div").select(selector);
                }
            }
        } catch (Selector.SelectorParseException e) {
            throw new UpstreamException("HTML site " + site.id() + " has an invalid selector in its settings");
        }
        int taken = 0;
        for (Element card : cards) {
            if (taken++ >= MAX_ITEMS_PER_PAGE) {
                break;
            }
            Element titleElement = card.selectFirst(site.title());
            if (titleElement == null || titleElement.text().isBlank()) {
                continue;
            }
            Element linkElement = !site.link().isBlank() ? card.selectFirst(site.link())
                    : titleElement.hasAttr("href") ? titleElement : card.selectFirst("a[href]");
            String href = linkElement == null ? "" : linkElement.absUrl("href");
            if (!href.startsWith("http://") && !href.startsWith("https://")) {
                continue;
            }
            String company = !site.company().isBlank() ? text(card, site.company()) : site.companyName();
            String location = site.location().isBlank() ? null : text(card, site.location());
            into.putIfAbsent(href, new RawPosting(ID, site.id() + ":" + sha1(com.vacancytracker.common.CanonicalUrl.of(href).orElse(href)), href, titleElement.text().trim(),
                    company == null || company.isBlank() ? site.id() : company, location, null, null, null, null, null, null, null));
        }
    }

    private static String text(Element card, String selector) {
        Element e = card.selectFirst(selector);
        return e == null ? null : e.text().trim();
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
