package com.vacancytracker.collection.adapter.himalayas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Himalayas public jobs API (T-038). Pages are read with the cursor from the previous response, at most
 * 20 jobs per request, up to a configured page cap. An optional key is sent as a Bearer token, which
 * gives higher limits than the shared anonymous allowance (official page). The apply link is kept as
 * the posting URL, as the terms require.
 */
@Component
public class HimalayasAdapter implements SourceAdapter {

    static final String ID = "himalayas";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int maxPages;
    private final String apiKey;

    public HimalayasAdapter(HttpFetcher fetcher, ObjectMapper mapper,
                            @Value("${app.sources.himalayas.base-url:https://himalayas.app}") String baseUrl,
                            @Value("${app.sources.himalayas.max-pages:3}") int maxPages,
                            @Value("${app.sources.himalayas.api-key:}") String apiKey) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.maxPages = maxPages;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
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
        boolean allUnchanged = true;
        String cursor = null;
        Map<String, String> headers = apiKey.isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
        for (int page = 1; page <= maxPages; page++) {
            String url = baseUrl + "/jobs/api?limit=20"
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            FetchResult result = fetcher.get(url, headers);
            if (result.notModified()) {
                break;
            }
            allUnchanged = false;
            if (!result.ok()) {
                throw new UpstreamException("Himalayas responded HTTP " + result.status());
            }
            JsonNode root = parse(result.body());
            for (JsonNode job : root.path("jobs")) {
                all.add(toPosting(job));
            }
            cursor = root.hasNonNull("nextCursor") ? root.get("nextCursor").asText() : null;
            if (cursor == null) {
                break;
            }
        }
        return allUnchanged ? FetchOutcome.noChange() : FetchOutcome.of(all);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new UpstreamException("Himalayas returned invalid JSON");
        }
    }

    private static RawPosting toPosting(JsonNode job) {
        String html = job.path("description").asText(null);
        return new RawPosting(
                ID,
                job.path("guid").asText(),
                job.path("applicationLink").asText(null),
                job.path("title").asText(),
                job.path("companyName").asText(null),
                locations(job.path("locationRestrictions")),
                "remote",
                html == null ? null : Normalizer.plainText(html),
                decimal(job.path("minSalary")),
                decimal(job.path("maxSalary")),
                job.hasNonNull("currency") ? job.get("currency").asText() : null,
                null,
                job.hasNonNull("pubDate") && job.get("pubDate").canConvertToLong()
                        ? Instant.ofEpochSecond(job.get("pubDate").asLong()) : null);
    }

    private static String locations(JsonNode restrictions) {
        if (!restrictions.isArray() || restrictions.isEmpty()) {
            return "Worldwide";
        }
        List<String> names = new ArrayList<>();
        restrictions.forEach(n -> names.add(n.asText()));
        return String.join(", ", names);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }
}
