package com.vacancytracker.collection.adapter.lever;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lever public postings API (T-032). GET needs no key; published postings are public by design
 * (README). Sites are configured by slug. Only published postings are returned by the endpoint.
 */
@Component
public class LeverAdapter implements SourceAdapter {

    static final String ID = "ats-lever";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final List<String> sites;
    private final String baseUrl;

    public LeverAdapter(
            HttpFetcher fetcher,
            ObjectMapper mapper,
            @Value("${app.sources.lever.sites:}") List<String> sites,
            @Value("${app.sources.lever.base-url:https://api.lever.co}") String baseUrl) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.sites = sites.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
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
        boolean allUnchanged = !sites.isEmpty();
        for (String site : sites) {
            FetchResult result = fetcher.getUnconditional(baseUrl + "/v0/postings/" + site + "?mode=json");
            if (result.notModified()) {
                continue;
            }
            allUnchanged = false;
            if (!result.ok()) {
                throw new UpstreamException("Lever site " + site + " responded HTTP " + result.status());
            }
            all.addAll(parse(site, result.body()));
        }
        return allUnchanged ? FetchOutcome.noChange() : FetchOutcome.of(all);
    }

    List<RawPosting> parse(String site, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) {
                throw new UpstreamException("Lever site " + site + " returned an unexpected shape");
            }
            List<RawPosting> out = new ArrayList<>();
            for (JsonNode posting : root) {
                out.add(toPosting(site, posting));
            }
            return out;
        } catch (IOException e) {
            throw new UpstreamException("Lever site " + site + " returned invalid JSON");
        }
    }

    private static RawPosting toPosting(String site, JsonNode p) {
        JsonNode categories = p.path("categories");
        return new RawPosting(
                ID,
                p.path("id").asText(),
                p.path("hostedUrl").asText(null),
                p.path("text").asText(),
                site,
                categories.path("location").asText(null),
                workMode(p.path("workplaceType").asText(null)),
                p.path("descriptionPlain").asText(null),
                null,
                null,
                null,
                null,
                p.hasNonNull("createdAt") ? Instant.ofEpochMilli(p.get("createdAt").asLong()) : null);
    }

    static String workMode(String lever) {
        if (lever == null) {
            return null;
        }
        return switch (lever) {
            case "remote" -> "remote";
            case "hybrid" -> "hybrid";
            case "on-site" -> "onsite";
            default -> null;
        };
    }
}
