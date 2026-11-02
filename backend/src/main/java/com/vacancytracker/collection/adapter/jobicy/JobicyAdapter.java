package com.vacancytracker.collection.adapter.jobicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.AdapterDates;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.CallBudget;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.collection.normalize.Normalizer;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Jobicy remote-jobs API (T-039). The docs ask for at most one automated pass per hour; the stored hourly
 * budget enforces that across restarts. The window is the last 7 days, so a run only adds new postings.
 */
@Component
public class JobicyAdapter implements SourceAdapter {

    static final String ID = "jobicy";

    private final HttpFetcher fetcher;
    private final CallBudget budget;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String baseUrl;
    private final String tag;

    public JobicyAdapter(HttpFetcher fetcher, CallBudget budget, ObjectMapper mapper, Clock clock,
                         @Value("${app.sources.jobicy.base-url:https://jobicy.com}") String baseUrl,
                         @Value("${app.sources.jobicy.tag:}") String tag) {
        this.fetcher = fetcher;
        this.budget = budget;
        this.mapper = mapper;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.tag = tag == null ? "" : tag.trim();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> serverSideFields() {
        return Set.of("keywords");
    }

    @Override
    public FetchOutcome fetch() {
        if (!budget.tryAcquireSpaced(ID, clock.instant(), java.time.Duration.ofHours(1))) {
            return FetchOutcome.noChange();
        }
        String url = baseUrl + "/api/jobs?count=100"
                + (tag.isEmpty() ? "" : "&tag=" + URLEncoder.encode(tag, StandardCharsets.UTF_8));
        FetchResult result = fetcher.getOnce(url);
        if (result.notModified()) {
            return FetchOutcome.noChange();
        }
        if (!result.ok()) {
            throw new UpstreamException("Jobicy responded HTTP " + result.status());
        }
        return FetchOutcome.of(parse(result.body()));
    }

    List<RawPosting> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<RawPosting> out = new ArrayList<>();
            for (JsonNode job : root.path("jobs")) {
                out.add(toPosting(job));
            }
            return out;
        } catch (IOException e) {
            throw new UpstreamException("Jobicy returned invalid JSON");
        }
    }

    private static RawPosting toPosting(JsonNode job) {
        String html = job.path("jobDescription").asText(null);
        return new RawPosting(
                ID,
                job.path("id").asText(),
                job.path("url").asText(null),
                job.path("jobTitle").asText(),
                job.path("companyName").asText(null),
                job.path("jobGeo").asText(null),
                "remote",
                html == null ? null : Normalizer.plainText(html),
                null,
                null,
                null,
                null,
                AdapterDates.instantOrNull(job.path("pubDate").asText(null)));
    }

}
