package com.vacancytracker.collection.adapter.ashby;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ashby public job board API (T-033). No key is needed. Postings with isListed=false are direct-link
 * only and are not collected (docs). Compensation is not mapped yet.
 */
@Component
public class AshbyAdapter implements SourceAdapter {

    static final String ID = "ats-ashby";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final List<String> boards;
    private final String baseUrl;

    public AshbyAdapter(
            HttpFetcher fetcher,
            ObjectMapper mapper,
            @Value("${app.sources.ashby.boards:}") List<String> boards,
            @Value("${app.sources.ashby.base-url:https://api.ashbyhq.com}") String baseUrl) {
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
            FetchResult result = fetcher.get(baseUrl + "/posting-api/job-board/" + board + "?includeCompensation=true");
            if (result.notModified()) {
                continue;
            }
            allUnchanged = false;
            if (!result.ok()) {
                throw new UpstreamException("Ashby board " + board + " responded HTTP " + result.status());
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
                if (job.path("isListed").asBoolean(true)) {
                    out.add(toPosting(board, job));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UpstreamException("Ashby board " + board + " returned invalid JSON");
        }
    }

    private static RawPosting toPosting(String board, JsonNode job) {
        return new RawPosting(
                ID,
                job.path("id").asText(),
                job.path("jobUrl").asText(null),
                job.path("title").asText(),
                board,
                job.path("location").asText(null),
                workMode(job.path("workplaceType").asText(null)),
                job.path("descriptionPlain").asText(null),
                null,
                null,
                null,
                null,
                AdapterDates.instantOrNull(job.path("publishedAt").asText(null)));
    }

    static String workMode(String ashby) {
        if (ashby == null) {
            return null;
        }
        return switch (ashby) {
            case "Remote" -> "remote";
            case "Hybrid" -> "hybrid";
            case "OnSite" -> "onsite";
            default -> null;
        };
    }
}
