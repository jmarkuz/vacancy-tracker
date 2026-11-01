package com.vacancytracker.collection.adapter.remotive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.CallBudget;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.collection.normalize.SalaryParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Remotive public remote-jobs API (T-037). Limits from the official docs: at most 4 requests per day
 * (enforced here by a stored daily budget) and no more than 2 per minute. Listings are delayed 24 hours.
 * When the daily budget is used up, the run reuses the last result (reported as unchanged).
 */
@Component
public class RemotiveAdapter implements SourceAdapter {

    static final String ID = "remotive";
    static final int DAILY_BUDGET = 4;

    private final HttpFetcher fetcher;
    private final CallBudget budget;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String baseUrl;
    private final String search;

    public RemotiveAdapter(HttpFetcher fetcher, CallBudget budget, ObjectMapper mapper, Clock clock,
                           @Value("${app.sources.remotive.base-url:https://remotive.com}") String baseUrl,
                           @Value("${app.sources.remotive.search:}") String search) {
        this.fetcher = fetcher;
        this.budget = budget;
        this.mapper = mapper;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.search = search;
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
        String day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        if (!budget.tryAcquire(ID, day, DAILY_BUDGET)) {
            return FetchOutcome.noChange();
        }
        String url = baseUrl + "/api/remote-jobs?limit=100"
                + (search.isBlank() ? "" : "&search=" + URLEncoder.encode(search, StandardCharsets.UTF_8));
        FetchResult result = fetcher.get(url);
        if (result.notModified()) {
            return FetchOutcome.noChange();
        }
        if (!result.ok()) {
            throw new UpstreamException("Remotive responded HTTP " + result.status());
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
            throw new UpstreamException("Remotive returned invalid JSON");
        }
    }

    private static RawPosting toPosting(JsonNode job) {
        Optional<SalaryParser.Salary> salary = SalaryParser.parse(job.path("salary").asText(""));
        return new RawPosting(
                ID,
                job.path("id").asText(),
                job.path("url").asText(null),
                job.path("title").asText(),
                job.path("company_name").asText(null),
                job.path("candidate_required_location").asText(null),
                "remote",
                job.path("description").asText(null),
                salary.map(SalaryParser.Salary::min).orElse(null),
                salary.map(SalaryParser.Salary::max).orElse(null),
                salary.map(SalaryParser.Salary::currency).orElse(null),
                salary.map(SalaryParser.Salary::period).orElse(null),
                publishedAt(job.path("publication_date").asText(null)));
    }

    /** Remotive sends local-looking timestamps without a zone; they are read as UTC. */
    static java.time.Instant publishedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim()).toInstant(ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
