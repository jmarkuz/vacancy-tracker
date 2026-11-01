package com.vacancytracker.collection.adapter.arbeitnow;

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
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Arbeitnow free job board API (T-034). No key; attribution is a link back to arbeitnow.com, and the
 * API may be revoked (terms section 11). Pages are read until no next link or the page cap is reached.
 */
@Component
public class ArbeitnowAdapter implements SourceAdapter {

    static final String ID = "arbeitnow";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int maxPages;

    public ArbeitnowAdapter(
            HttpFetcher fetcher,
            ObjectMapper mapper,
            @Value("${app.sources.arbeitnow.base-url:https://www.arbeitnow.com}") String baseUrl,
            @Value("${app.sources.arbeitnow.max-pages:3}") int maxPages) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.maxPages = maxPages;
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
        List<RawPosting> all = new ArrayList<>();
        boolean allUnchanged = true;
        for (int page = 1; page <= maxPages; page++) {
            FetchResult result = fetcher.get(baseUrl + "/api/job-board-api?page=" + page);
            if (result.notModified()) {
                continue;
            }
            allUnchanged = false;
            if (!result.ok()) {
                throw new UpstreamException("Arbeitnow page " + page + " responded HTTP " + result.status());
            }
            ParsedPage parsed = parse(result.body());
            all.addAll(parsed.postings());
            if (!parsed.hasNext()) {
                break;
            }
        }
        return allUnchanged ? FetchOutcome.noChange() : FetchOutcome.of(all);
    }

    record ParsedPage(List<RawPosting> postings, boolean hasNext) {
    }

    ParsedPage parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<RawPosting> out = new ArrayList<>();
            for (JsonNode job : root.path("data")) {
                out.add(toPosting(job));
            }
            boolean hasNext = root.path("links").hasNonNull("next") && !root.path("data").isEmpty();
            return new ParsedPage(out, hasNext);
        } catch (IOException e) {
            throw new UpstreamException("Arbeitnow returned invalid JSON");
        }
    }

    private static RawPosting toPosting(JsonNode job) {
        String html = job.path("description").asText("");
        return new RawPosting(
                ID,
                job.path("slug").asText(),
                job.path("url").asText(null),
                job.path("title").asText(),
                job.path("company_name").asText(null),
                job.path("location").asText(null),
                job.path("remote").asBoolean(false) ? "remote" : null,
                html.isEmpty() ? null : Jsoup.parse(html).text(),
                null,
                null,
                null,
                null,
                job.hasNonNull("created_at") ? Instant.ofEpochSecond(job.get("created_at").asLong()) : null);
    }
}
