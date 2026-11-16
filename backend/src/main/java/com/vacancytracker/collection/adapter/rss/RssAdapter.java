package com.vacancytracker.collection.adapter.rss;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.collection.normalize.Normalizer;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Generic RSS and Atom adapter (T-040). Reads only feeds that are enabled in the configuration and carry a recorded
 * terms check. Item text is HTML in most feeds, so it is reduced to plain text. A feed that cannot be read does not
 * stop the others; the run fails only when no feed could be read. DOCTYPE declarations are refused by the parser.
 */
@Component
@EnableConfigurationProperties(RssSourcesProperties.class)
public class RssAdapter implements SourceAdapter {

    public static final String ID = "rss";
    static final int MAX_ITEMS_PER_FEED = 200;
    private static final Logger LOG = LoggerFactory.getLogger(RssAdapter.class);

    private final HttpFetcher fetcher;
    private final List<RssFeed> feeds;

    @Autowired
    public RssAdapter(HttpFetcher fetcher, RssSourcesProperties properties) {
        this.fetcher = fetcher;
        this.feeds = properties.feeds() == null ? List.of() : properties.feeds();
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
        for (RssFeed feed : feeds) {
            if (!feed.approved()) {
                if (feed.enabled()) {
                    LOG.warn("RSS feed '{}' is enabled but has no recorded terms check or is incomplete; it is not read", feed.id());
                }
                continue;
            }
            try {
                postings.addAll(readFeed(feed));
                read++;
            } catch (UpstreamException | IllegalArgumentException e) {
                failed++;
                String reason = "RSS feed " + feed.id() + ": " + e.getMessage();
                firstError = firstError == null ? reason : firstError;
                LOG.warn("{}", reason);
            }
        }
        if (read == 0 && failed > 0) {
            throw new UpstreamException("No RSS feed could be read. " + firstError);
        }
        FetchOutcome outcome = FetchOutcome.of(postings);
        return failed > 0 ? outcome.withWarning(failed + " of " + (read + failed) + " feeds could not be read. " + firstError) : outcome;
    }

    private List<RawPosting> readFeed(RssFeed feed) {
        FetchResult result = fetcher.getUnconditional(feed.url());
        if (result.notModified()) {
            return List.of();
        }
        if (!result.ok()) {
            throw new UpstreamException("responded HTTP " + result.status());
        }
        return parse(feed, result.body());
    }

    List<RawPosting> parse(RssFeed feed, String xml) {
        SyndFeed parsed;
        try {
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(false);
            parsed = input.build(new StringReader(xml));
        } catch (FeedException | IllegalArgumentException e) {
            throw new UpstreamException("the feed is not valid RSS or Atom");
        }
        Map<String, RawPosting> found = new LinkedHashMap<>();
        int taken = 0;
        for (SyndEntry entry : parsed.getEntries()) {
            if (taken++ >= MAX_ITEMS_PER_FEED) {
                break;
            }
            String link = entry.getLink();
            String title = entry.getTitle() == null ? "" : entry.getTitle().trim();
            if (title.isEmpty() || link == null || !(link.regionMatches(true, 0, "http://", 0, 7) || link.regionMatches(true, 0, "https://", 0, 8))) {
                continue;
            }
            String id = entry.getUri() != null && !entry.getUri().isBlank() ? entry.getUri() : link;
            String html = fullerText(entry);
            String author = authorName(entry.getAuthor());
            String company = !feed.companyName().isBlank() ? feed.companyName() : !author.isEmpty() ? author : feed.id();
            found.putIfAbsent(id, new RawPosting(ID, feed.id() + ":" + id, link, title, company, null, null,
                    html == null ? null : Normalizer.plainText(html), null, null, null, null,
                    postedAt(entry)));
        }
        return List.copyOf(found.values());
    }

    /**
     * A feed may carry a teaser in description and the whole text in content:encoded (or Atom content); the longer
     * one is the posting's text.
     */
    private static String fullerText(SyndEntry entry) {
        String summary = entry.getDescription() == null ? null : entry.getDescription().getValue();
        String content = entry.getContents().isEmpty() ? null : entry.getContents().get(0).getValue();
        if (summary == null || content != null && Normalizer.plainText(content) != null
                && (Normalizer.plainText(summary) == null || Normalizer.plainText(content).length() > Normalizer.plainText(summary).length())) {
            return content;
        }
        return summary;
    }

    /**
     * RSS 2.0 requires an e-mail address in author, with the name in brackets: "jobs@acme.test (Acme Labs)". The name is
     * the company; a bare address is no company name, so it gives none.
     */
    static String authorName(String author) {
        if (author == null) {
            return "";
        }
        String text = author.trim();
        java.util.regex.Matcher named = java.util.regex.Pattern.compile("^\\S+@\\S+\\s*\\((.+)\\)$").matcher(text);
        if (named.matches()) {
            return named.group(1).trim();
        }
        return text.contains("@") && !text.contains(" ") ? "" : text;
    }

    /** Atom requires only "updated", so it stands in when "published" is absent. */
    private static java.time.Instant postedAt(SyndEntry entry) {
        java.util.Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return date == null ? null : date.toInstant();
    }
}
