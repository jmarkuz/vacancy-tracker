package com.vacancytracker.collection.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one HTTP client for all adapters (ADR-018): custom User-Agent, timeouts, per-host spacing,
 * conditional requests, and retries on 429 and 5xx with Retry-After support.
 * Other 4xx responses are returned to the caller, who decides what they mean.
 */
@Component
public class HttpFetcher {

    /** The name robots.txt rules are matched against (the product part of the User-Agent). */
    public static final String PRODUCT_TOKEN = "VacancyTracker";
    static final String USER_AGENT = PRODUCT_TOKEN + "/0.1 (personal job search; no crawling)";
    static final int MAX_BODY_CHARS = 5 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final DomainRateLimiter limiter;
    private final ConditionalCache cache;
    private final RetryPolicy retry;
    private final Sleeper sleeper;

    @Autowired
    public HttpFetcher(ConditionalCache cache, Clock clock) {
        this(cache, RetryPolicy.standard(), new DomainRateLimiter(1000, clock, Sleeper.THREAD), Sleeper.THREAD);
    }

    /** Explicit wiring for tests and for custom retry or pacing settings. */
    public HttpFetcher(ConditionalCache cache, RetryPolicy retry, DomainRateLimiter limiter, Sleeper sleeper) {
        this.cache = cache;
        this.retry = retry;
        this.limiter = limiter;
        this.sleeper = sleeper;
    }

    public FetchResult get(String url) {
        return get(url, Map.of());
    }

    public FetchResult get(String url, Map<String, String> headers) {
        return get(url, url, headers);
    }

    /**
     * cacheKey identifies the validators in the cache. Sources that put secrets in the query string pass a
     * key without them, so keys are never stored in the database.
     */
    public FetchResult get(String url, String cacheKey, Map<String, String> headers) {
        return fetch(url, cacheKey, headers, true);
    }

    /**
     * A GET that sends no validators and stores none, so the answer is always the full page. For sources whose
     * pages are parsed by the adapter itself and where "not modified" would hide which items still exist.
     */
    public FetchResult getUnconditional(String url) {
        return fetch(url, url, Map.of(), false);
    }

    private FetchResult fetch(String url, String cacheKey, Map<String, String> headers, boolean conditional) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            // The standard message quotes the whole address, which can carry a key.
            throw new UpstreamException("The address of the source is not valid");
        }
        requireHttp(uri);
        Optional<ConditionalCache.Validators> known = conditional ? cache.find(cacheKey) : Optional.empty();
        for (int attempt = 1; ; attempt++) {
            limiter.acquire(uri.getHost());
            HttpResponse<String> response;
            try {
                response = client.send(request(uri, known, headers), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt >= retry.maxAttempts()) {
                    throw new UpstreamException("Network error after " + attempt + " attempts: " + e.getMessage());
                }
                sleeper.sleep(retry.delayMillis(attempt, null));
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException("Interrupted");
            }
            int status = response.statusCode();
            if (status == 304 && known.isPresent()) {
                return FetchResult.unchanged();
            }
            if (status == 429 || status / 100 == 5) {
                if (attempt >= retry.maxAttempts()) {
                    throw new UpstreamException("HTTP " + status + " after " + attempt + " attempts");
                }
                Duration retryAfter = response.headers().firstValue("Retry-After").map(HttpFetcher::seconds).orElse(null);
                sleeper.sleep(retry.delayMillis(attempt, retryAfter));
                continue;
            }
            return finish(conditional ? cacheKey : null, response, status);
        }
    }

    private FetchResult finish(String cacheKey, HttpResponse<String> response, int status) {
        String body = response.body();
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS);
        }
        String etag = response.headers().firstValue("ETag").orElse(null);
        String lastModified = response.headers().firstValue("Last-Modified").orElse(null);
        if (cacheKey != null && status / 100 == 2 && (etag != null || lastModified != null)) {
            cache.save(cacheKey, new ConditionalCache.Validators(etag, lastModified));
        }
        return new FetchResult(status, body, etag, lastModified, false);
    }

    private static HttpRequest request(URI uri, Optional<ConditionalCache.Validators> known, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/rss+xml, application/xml;q=0.9, text/html;q=0.8");
        known.ifPresent(v -> {
            if (v.etag() != null) {
                builder.header("If-None-Match", v.etag());
            }
            if (v.lastModified() != null) {
                builder.header("If-Modified-Since", v.lastModified());
            }
        });
        headers.forEach(builder::header);
        return builder.GET().build();
    }

    private static void requireHttp(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only http and https URLs can be fetched");
        }
    }

    private static Duration seconds(String value) {
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
