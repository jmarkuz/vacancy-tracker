package com.vacancytracker.collection.adapter.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.AdapterDates;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Greenhouse public Job Board API (T-031). No key is needed for GET (docs: "Job Board data is publicly
 * available"). Boards are configured by token; each board is one request with content=true.
 */
@Component
public class GreenhouseAdapter implements SourceAdapter {

    static final String ID = "ats-greenhouse";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final List<String> boards;
    private final String baseUrl;

    public GreenhouseAdapter(
            HttpFetcher fetcher,
            ObjectMapper mapper,
            @Value("${app.sources.greenhouse.boards:}") List<String> boards,
            @Value("${app.sources.greenhouse.base-url:https://boards-api.greenhouse.io}") String baseUrl) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.boards = boards.stream().map(String::trim).filter(b -> !b.isEmpty()).toList();
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
        List<RawPosting> all = new ArrayList<>();
        boolean allUnchanged = !boards.isEmpty();
        for (String board : boards) {
            FetchResult result = fetcher.getUnconditional(baseUrl + "/v1/boards/" + board + "/jobs?content=true");
            if (result.notModified()) {
                continue;
            }
            allUnchanged = false;
            if (!result.ok()) {
                throw new UpstreamException("Greenhouse board " + board + " responded HTTP " + result.status());
            }
            all.addAll(parse(board, result.body()));
        }
        return allUnchanged ? FetchOutcome.noChange() : FetchOutcome.of(all);
    }

    List<RawPosting> parse(String board, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<RawPosting> out = new ArrayList<>();
            for (JsonNode job : root.path("jobs")) {
                out.add(toPosting(board, job));
            }
            return out;
        } catch (IOException e) {
            throw new UpstreamException("Greenhouse board " + board + " returned invalid JSON");
        }
    }

    /** Greenhouse sends HTML entity-escaped (&lt;p&gt;), so the text is parsed twice to reach plain text. */
    static String plainText(String escapedHtml) {
        return Jsoup.parse(Jsoup.parse(escapedHtml).text()).text();
    }

    private static RawPosting toPosting(String board, JsonNode job) {
        String content = job.path("content").asText("");
        return new RawPosting(
                ID,
                job.path("id").asText(),
                job.path("absolute_url").asText(null),
                job.path("title").asText(),
                board,
                job.path("location").path("name").asText(null),
                null,
                content.isEmpty() ? null : plainText(content),
                null,
                null,
                null,
                null,
                AdapterDates.instantOrNull(job.path("updated_at").asText(null)));
    }
}
