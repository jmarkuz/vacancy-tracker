package com.vacancytracker.collection.adapter.remoteok;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Remote OK public JSON API (T-089, docs/sources.md). One request per run returns the current listings; element 0 is
 * Remote OK's legal notice and is skipped. Terms: credit Remote OK with a normal link back, so each vacancy keeps
 * the Remote OK page as its link (the posting's own apply link is in the description), and the source is shown as
 * Remote OK. A window of recent jobs, so these vacancies never expire automatically (ADR-037).
 */
@Component
public class RemoteOkAdapter implements SourceAdapter {

    static final String ID = "remoteok";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String baseUrl;

    public RemoteOkAdapter(HttpFetcher fetcher, ObjectMapper mapper,
                           @Value("${app.sources.remoteok.enabled:true}") boolean enabled,
                           @Value("${app.sources.remoteok.base-url:https://remoteok.com}") String baseUrl) {
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
        FetchResult result = fetcher.getUnconditional(baseUrl + "/api");
        if (!result.ok()) {
            throw new UpstreamException("Remote OK responded HTTP " + result.status());
        }
        return FetchOutcome.of(parse(result.body()));
    }

    List<RawPosting> parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) {
                throw new UpstreamException("Remote OK returned no list of jobs");
            }
            List<RawPosting> out = new ArrayList<>();
            for (JsonNode job : root) {
                // The first element is the legal notice; real jobs have an id and a position.
                if (job.hasNonNull("id") && job.hasNonNull("position")) {
                    out.add(toPosting(job));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UpstreamException("Remote OK returned invalid JSON");
        }
    }

    private static RawPosting toPosting(JsonNode job) {
        String html = job.path("description").asText(null);
        String apply = job.path("apply_url").asText(null);
        String description = html == null ? null : Normalizer.plainText(html);
        if (apply != null && !apply.isBlank()) {
            description = (description == null ? "" : description + "\n\n") + "Apply: " + apply;
        }
        Instant posted = job.hasNonNull("epoch") ? Instant.ofEpochSecond(job.path("epoch").asLong())
                : AdapterDates.instantOrNull(job.path("date").asText(null));
        return new RawPosting(
                ID,
                job.path("id").asText(),
                job.path("url").asText(null),
                job.path("position").asText(),
                job.path("company").asText(null),
                job.path("location").asText(null),
                "remote",
                description,
                amount(job.path("salary_min")),
                amount(job.path("salary_max")),
                amount(job.path("salary_min")) != null || amount(job.path("salary_max")) != null ? "USD" : null,
                amount(job.path("salary_min")) != null || amount(job.path("salary_max")) != null ? "year" : null,
                posted);
    }

    /** Remote OK states salaries in US dollars a year; 0 means "not given". */
    private static BigDecimal amount(JsonNode node) {
        return node.isNumber() && node.asLong() > 0 ? BigDecimal.valueOf(node.asLong()) : null;
    }
}
