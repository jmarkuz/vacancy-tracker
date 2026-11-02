package com.vacancytracker.collection.adapter.workingnomads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.AdapterDates;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.collection.normalize.Normalizer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Working Nomads public API (T-089, docs/sources.md): the "API" link in the site's menu returns the current remote
 * jobs as one JSON array, with no key, filters or pages, so everything is filtered after fetching. The terms have no
 * clause on automated reading; the only restriction is not to build a competing service. A window of current jobs,
 * so these vacancies never expire automatically (ADR-037).
 */
@Component
public class WorkingNomadsAdapter implements SourceAdapter {

    static final String ID = "workingnomads";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String baseUrl;

    public WorkingNomadsAdapter(HttpFetcher fetcher, ObjectMapper mapper,
                                @Value("${app.sources.workingnomads.enabled:true}") boolean enabled,
                                @Value("${app.sources.workingnomads.base-url:https://www.workingnomads.com}") String baseUrl) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.enabled = enabled;
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
        if (!enabled) {
            return FetchOutcome.noChange();
        }
        FetchResult result = fetcher.getUnconditional(baseUrl + "/api/exposed_jobs/");
        if (!result.ok()) {
            throw new UpstreamException("Working Nomads responded HTTP " + result.status());
        }
        return FetchOutcome.of(parse(result.body()));
    }

    List<RawPosting> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) {
                throw new UpstreamException("Working Nomads returned no list of jobs");
            }
            List<RawPosting> out = new ArrayList<>();
            for (JsonNode job : root) {
                String url = job.path("url").asText(null);
                if (url == null || job.path("title").asText("").isBlank()) {
                    continue;
                }
                String html = job.path("description").asText(null);
                out.add(new RawPosting(
                        ID,
                        url,
                        url,
                        job.path("title").asText(),
                        job.path("company_name").asText(null),
                        job.path("location").asText(null),
                        "remote",
                        html == null ? null : Normalizer.plainText(html),
                        null,
                        null,
                        null,
                        null,
                        AdapterDates.instantOrNull(job.path("pub_date").asText(null))));
            }
            return out;
        } catch (IOException e) {
            throw new UpstreamException("Working Nomads returned invalid JSON");
        }
    }
}
