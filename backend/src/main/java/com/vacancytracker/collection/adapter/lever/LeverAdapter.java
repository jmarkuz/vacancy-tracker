package com.vacancytracker.collection.adapter.lever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.AdapterDates;
import com.vacancytracker.collection.adapter.BoardFetch;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.collection.normalize.Normalizer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lever public postings API (T-032). GET needs no key; published postings are public by design
 * (README). Sites are configured by slug. Only published postings are returned by the endpoint.
 */
@Component
public class LeverAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(LeverAdapter.class);

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
    public java.util.Optional<Set<String>> followedCompanies() {
        return java.util.Optional.of(sites.stream().map(b -> b.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet()));
    }

    /** A site that cannot be read does not stop the others (see {@link BoardFetch}). */
    @Override
    public FetchOutcome fetch() {
        return BoardFetch.fetchAll("Lever site", sites, this::readSite, LOG);
    }

    private Optional<List<RawPosting>> readSite(String site) {
        FetchResult result = fetcher.getUnconditional(baseUrl + "/v0/postings/" + site + "?mode=json");
        if (result.notModified()) {
            return Optional.empty();
        }
        if (!result.ok()) {
            throw new UpstreamException("Lever site " + site + " responded HTTP " + result.status());
        }
        return Optional.of(parse(site, result.body()));
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
        JsonNode salary = p.path("salaryRange");
        return new RawPosting(
                ID,
                p.path("id").asText(),
                p.path("hostedUrl").asText(null),
                p.path("text").asText(),
                site,
                location(categories),
                workMode(p.path("workplaceType").asText(null)),
                description(p),
                decimal(salary.path("min")),
                decimal(salary.path("max")),
                salary.hasNonNull("currency") ? salary.get("currency").asText() : null,
                salary.hasNonNull("interval") ? salary.get("interval").asText() : null,
                AdapterDates.fromNode(p.path("createdAt")),
                p.hasNonNull("country") ? p.get("country").asText() : null);
    }

    /** The posting's place; for a role open in several places without a main one, the places it lists. */
    private static String location(JsonNode categories) {
        String location = categories.path("location").asText(null);
        if (location != null && !location.isBlank()) {
            return location;
        }
        List<String> all = new ArrayList<>();
        categories.path("allLocations").forEach(n -> all.add(n.asText()));
        return all.isEmpty() ? null : String.join(", ", all);
    }

    /**
     * Lever splits a posting in parts: the opening text, lists (responsibilities, requirements: a title and HTML items)
     * and a closing text. descriptionPlain alone is only the opening, so the parts are joined.
     */
    private static String description(JsonNode p) {
        StringBuilder text = new StringBuilder(p.path("descriptionPlain").asText(""));
        for (JsonNode list : p.path("lists")) {
            text.append("\n").append(list.path("text").asText(""));
            String items = Normalizer.plainText(list.path("content").asText(""));
            if (items != null) {
                text.append("\n").append(items);
            }
        }
        text.append("\n").append(p.path("additionalPlain").asText(""));
        String joined = text.toString().strip();
        return joined.isEmpty() ? null : joined;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
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
