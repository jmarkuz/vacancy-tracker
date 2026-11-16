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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ashby public job board API (T-033). No key is needed. Postings with isListed=false are direct-link
 * only and are not collected (docs). The salary component of the compensation is mapped.
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
    public java.util.Optional<Set<String>> followedCompanies() {
        return java.util.Optional.of(boards.stream().map(b -> b.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet()));
    }

    @Override
    public FetchOutcome fetch() {
        List<RawPosting> all = new ArrayList<>();
        boolean allUnchanged = !boards.isEmpty();
        for (String board : boards) {
            FetchResult result = fetcher.getUnconditional(baseUrl + "/posting-api/job-board/" + board + "?includeCompensation=true");
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
        JsonNode salary = salaryComponent(job.path("compensation").path("summaryComponents"));
        String workMode = workMode(job.path("workplaceType").asText(null));
        if (workMode == null && job.path("isRemote").asBoolean(false)) {
            workMode = "remote";
        }
        return new RawPosting(
                ID,
                job.path("id").asText(),
                job.path("jobUrl").asText(null),
                job.path("title").asText(),
                board,
                job.path("location").asText(null),
                workMode,
                job.path("descriptionPlain").asText(null),
                decimal(salary.path("minValue")),
                decimal(salary.path("maxValue")),
                salary.hasNonNull("currencyCode") ? salary.get("currencyCode").asText() : null,
                salary.hasNonNull("interval") ? salary.get("interval").asText() : null,
                AdapterDates.instantOrNull(job.path("publishedAt").asText(null)),
                job.path("address").path("postalAddress").hasNonNull("addressCountry")
                        ? job.path("address").path("postalAddress").get("addressCountry").asText() : null);
    }

    /** The base salary among the compensation components (bonus and equity are not salary); missing when there is none. */
    private static JsonNode salaryComponent(JsonNode components) {
        for (JsonNode component : components) {
            if ("Salary".equalsIgnoreCase(component.path("compensationType").asText())) {
                return component;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
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
