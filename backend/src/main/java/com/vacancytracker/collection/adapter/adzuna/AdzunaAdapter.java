package com.vacancytracker.collection.adapter.adzuna;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adzuna job search API (T-035). Requires app_id and app_key, which are sent as query parameters, so the
 * request URL is never used as a cache key (the keys would be stored). Countries come from configuration,
 * and so do currencies: salaries are kept only when the currency of the country is configured.
 * Permitted use per the terms: personal job search. Other use needs a written agreement.
 */
@Component
public class AdzunaAdapter implements SourceAdapter {

    static final String ID = "adzuna";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String appId;
    private final String appKey;
    private final List<String> countries;
    private final Map<String, String> currencies;
    private final String what;
    private final int maxPages;

    public AdzunaAdapter(HttpFetcher fetcher, ObjectMapper mapper,
                         @Value("${app.sources.adzuna.base-url:https://api.adzuna.com}") String baseUrl,
                         @Value("${app.sources.adzuna.app-id:}") String appId,
                         @Value("${app.sources.adzuna.app-key:}") String appKey,
                         @Value("${app.sources.adzuna.countries:}") List<String> countries,
                         @Value("${app.sources.adzuna.currencies:}") String currencies,
                         @Value("${app.sources.adzuna.what:}") String what,
                         @Value("${app.sources.adzuna.max-pages:1}") int maxPages) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.appId = appId == null ? "" : appId.trim();
        this.appKey = appKey == null ? "" : appKey.trim();
        this.countries = countries.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
        this.currencies = parseCurrencies(currencies);
        this.what = what == null ? "" : what.trim();
        this.maxPages = maxPages;
    }

    /** "gb=GBP,us=USD" becomes a map from country code to currency code. */
    static Map<String, String> parseCurrencies(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                out.put(parts[0].trim().toLowerCase(java.util.Locale.ROOT), parts[1].trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Country codes Adzuna's public documentation lists for the search endpoint. The official page does not
     * render a machine-readable list (docs/sources.md), so this is the set the API is known to serve; a code
     * outside it is refused before any request, which also keeps the value safe to place in the URL path.
     */
    static final Set<String> SUPPORTED_COUNTRIES = Set.of("at", "au", "be", "br", "ca", "ch", "de", "es", "fr",
            "gb", "in", "it", "mx", "nl", "nz", "pl", "sg", "us", "za");

    @Override
    public Set<String> serverSideFields() {
        return Set.of("keywords", "location", "country");
    }

    @Override
    public FetchOutcome fetch() {
        if (appId.isEmpty() || appKey.isEmpty() || countries.isEmpty()) {
            return FetchOutcome.noChange();
        }
        for (String country : countries) {
            if (!SUPPORTED_COUNTRIES.contains(country)) {
                throw new UpstreamException("Adzuna country '" + country + "' is not one of the supported codes");
            }
        }
        List<RawPosting> all = new ArrayList<>();
        for (String country : countries) {
            for (int page = 1; page <= maxPages; page++) {
                String path = "/v1/api/jobs/" + country + "/search/" + page;
                String query = "results_per_page=50" + (what.isEmpty() ? "" : "&what=" + enc(what));
                String cacheKey = baseUrl + path + "?" + query;
                String url = cacheKey + "&app_id=" + enc(appId) + "&app_key=" + enc(appKey);
                FetchResult result = fetcher.get(url, cacheKey, Map.of());
                if (!result.ok()) {
                    throw new UpstreamException("Adzuna " + country + " responded HTTP " + result.status());
                }
                if (result.notModified()) {
                    continue;
                }
                JsonNode root = parse(result.body(), country);
                root.path("results").forEach(job -> all.add(toPosting(country, job)));
                if (root.path("results").size() < 50) {
                    break;
                }
            }
        }
        return FetchOutcome.of(all);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode parse(String json, String country) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new UpstreamException("Adzuna " + country + " returned invalid JSON");
        }
    }

    private RawPosting toPosting(String country, JsonNode job) {
        String currency = currencies.get(country);
        BigDecimal min = decimal(job.path("salary_min"));
        BigDecimal max = decimal(job.path("salary_max"));
        return new RawPosting(
                ID,
                job.path("id").asText(),
                job.path("redirect_url").asText(null),
                job.path("title").asText(),
                job.path("company").path("display_name").asText(null),
                job.path("location").path("display_name").asText(null),
                null,
                Normalizer.plainText(job.path("description").asText(null)),
                min,
                max,
                currency,
                // The period is not stated by the docs read in T-027, so it is left unknown rather than guessed.
                null,
                AdapterDates.instantOrNull(job.path("created").asText(null)));
    }


    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }
}
