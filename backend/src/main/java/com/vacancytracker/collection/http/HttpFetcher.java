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
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * The largest answer body that is read, in MB. Whole-list sources (a Greenhouse board with content=true, Remote OK,
     * Working Nomads) answer with several MB of JSON at once, so 5 MB was too small and failed such a source on every
     * run. Configurable with app.collection.http.max-body-mb (APP_COLLECTION_HTTP_MAX_BODY_MB). A body is held as bytes,
     * a string and a parsed tree at once, so the value is kept within MIN..MAX_BODY_MB_LIMIT to protect the heap.
     */
    static final int DEFAULT_MAX_BODY_MB = 25;
    static final int MIN_MAX_BODY_MB = 1;
    static final int MAX_BODY_MB_LIMIT = 256;
    private static final long MB = 1024L * 1024L;
    static final int MAX_REDIRECTS = 3;
    /** Deadline for reading a whole answer body: the request timeout only covers the headers (T-092, F7). */
    public static final Duration BODY_DEADLINE = Duration.ofSeconds(30);

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "http-body-deadline");
                t.setDaemon(true);
                return t;
            });

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final DomainRateLimiter limiter;
    private final ConditionalCache cache;
    private final RetryPolicy retry;
    private final Sleeper sleeper;
    private final long maxBodyBytes;

    @Autowired
    public HttpFetcher(ConditionalCache cache, Clock clock,
                       @Value("${app.collection.http.max-body-mb:" + DEFAULT_MAX_BODY_MB + "}") int maxBodyMb) {
        this(cache, RetryPolicy.standard(), new DomainRateLimiter(1000, clock, Sleeper.THREAD), Sleeper.THREAD,
                Math.max(MIN_MAX_BODY_MB, Math.min(MAX_BODY_MB_LIMIT, maxBodyMb)) * MB);
    }

    /** Explicit wiring for tests and for custom retry or pacing settings, with the default size limit. */
    public HttpFetcher(ConditionalCache cache, RetryPolicy retry, DomainRateLimiter limiter, Sleeper sleeper) {
        this(cache, retry, limiter, sleeper, DEFAULT_MAX_BODY_MB * MB);
    }

    /** As above with an explicit limit in bytes for one answer body. */
    public HttpFetcher(ConditionalCache cache, RetryPolicy retry, DomainRateLimiter limiter, Sleeper sleeper, long maxBodyBytes) {
        this.cache = cache;
        this.retry = retry;
        this.limiter = limiter;
        this.sleeper = sleeper;
        this.maxBodyBytes = maxBodyBytes;
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
        return fetch(url, cacheKey, headers, true, MAX_REDIRECTS, retry.maxAttempts());
    }

    /**
     * A GET that sends no validators and stores none, so the answer is always the full page. For sources whose
     * pages are parsed by the adapter itself and where "not modified" would hide which items still exist.
     */
    public FetchResult getUnconditional(String url) {
        return fetch(url, url, Map.of(), false, MAX_REDIRECTS, retry.maxAttempts());
    }

    /**
     * A conditional GET that is tried once. For sources with a hard call quota: a retry would be another call that
     * the quota does not know about. The next scheduled run is the retry.
     */
    public FetchResult getOnce(String url) {
        return fetch(url, url, Map.of(), true, MAX_REDIRECTS, 1);
    }

    /**
     * An unconditional GET tried once: no validators are sent or stored, so the address never becomes a cache key.
     * For addresses that carry a secret in the path (a Telegram bot token) and for answers that are never repeated.
     */
    public FetchResult getUnconditionalOnce(String url) {
        return fetch(url, url, Map.of(), false, MAX_REDIRECTS, 1);
    }

    /** As {@link #getUnconditional(String)} with extra headers, for sources that need a key in a header. */
    public FetchResult getUnconditional(String url, Map<String, String> headers) {
        return fetch(url, url, headers, false, MAX_REDIRECTS, retry.maxAttempts());
    }

    /**
     * An unconditional GET that follows no redirect: a 3xx comes back as the answer. For pages whose permission was
     * checked for exactly this address (robots.txt), where a redirect would land on an unchecked one.
     */
    public FetchResult getUnconditionalWithoutRedirects(String url) {
        return fetch(url, url, Map.of(), false, 0, retry.maxAttempts());
    }

    private FetchResult fetch(String url, String cacheKey, Map<String, String> headers, boolean conditional, int redirectsLeft, int attempts) {
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
            HttpResponse<java.io.InputStream> response;
            try {
                response = client.send(request(uri, known, headers), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                if (attempt >= attempts) {
                    throw new UpstreamException("Network error after " + attempt + " attempts: " + e.getMessage());
                }
                sleeper.sleep(retry.delayMillis(attempt, null));
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException("Interrupted");
            }
            int status = response.statusCode();
            if (isRedirect(status) && redirectsLeft > 0) {
                closeQuietly(response);
                URI next = redirectTarget(uri, response);
                // Same host only: the request headers (a key, for some sources) and the pacing belong to this host. The target
                // is fetched without validators and none are stored: those of the first address do not describe it, and its
                // own address must not become a cache key.
                return fetch(next.toString(), cacheKey, headers, false, redirectsLeft - 1, attempts);
            }
            if (status == 304 && known.isPresent()) {
                closeQuietly(response);
                return FetchResult.unchanged();
            }
            if (status == 429 || status / 100 == 5) {
                closeQuietly(response);
                if (attempt >= attempts) {
                    throw new UpstreamException("HTTP " + status + " after " + attempt + " attempts");
                }
                Duration retryAfter = response.headers().firstValue("Retry-After").map(HttpFetcher::seconds).orElse(null);
                sleeper.sleep(retry.delayMillis(attempt, retryAfter));
                continue;
            }
            return finish(conditional ? cacheKey : null, response, status);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI redirectTarget(URI from, HttpResponse<?> response) {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) {
            throw new UpstreamException("HTTP " + response.statusCode() + " without a Location");
        }
        URI next;
        try {
            next = from.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new UpstreamException("The source redirected to an address that is not valid");
        }
        requireHttp(next);
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
            throw new UpstreamException("The source redirected from https to http, which is not followed");
        }
        if (next.getHost() == null || !next.getHost().equalsIgnoreCase(from.getHost())) {
            throw new UpstreamException("The source redirected to another host, which is not followed");
        }
        return next;
    }

    private static void closeQuietly(HttpResponse<java.io.InputStream> response) {
        closeQuietly(response.body());
    }

    private static void closeQuietly(java.io.InputStream body) {
        try {
            body.close();
        } catch (IOException e) {
            // nothing to do: the connection is dropped either way
        }
    }

    /** The charset of the Content-Type header; UTF-8 when it is missing or unknown. Pages in windows-1250 stay readable. */
    public static java.nio.charset.Charset charsetOf(java.net.http.HttpHeaders headers) {
        String type = headers.firstValue("Content-Type").orElse("");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9_.:-]+)").matcher(type);
        if (m.find()) {
            try {
                return java.nio.charset.Charset.forName(m.group(1));
            } catch (IllegalArgumentException e) {
                return java.nio.charset.StandardCharsets.UTF_8;
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private String readBounded(HttpResponse<java.io.InputStream> response) {
        return readBounded(response, BODY_DEADLINE, maxBodyBytes);
    }

    static String readBounded(HttpResponse<java.io.InputStream> response, Duration deadline) {
        return readBounded(response, deadline, DEFAULT_MAX_BODY_MB * MB);
    }

    /**
     * Reads the whole body up to maxBytes; a larger answer is refused, not cut, so a half document is never parsed.
     * A stalled body is cut by closing the stream when the deadline passes, so the run lock is never held for good.
     */
    static String readBounded(HttpResponse<java.io.InputStream> response, Duration deadline, long maxBytes) {
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        java.io.InputStream body = response.body();
        java.util.concurrent.ScheduledFuture<?> timer = WATCHDOG.schedule(() -> {
            expired.set(true);
            closeQuietly(body);
        }, deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        try (java.io.InputStream in = body) {
            byte[] bytes = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
            if (bytes.length > maxBytes) {
                throw new UpstreamException("The answer is larger than " + describe(maxBytes)
                        + ", the limit for one answer. Raise it with APP_COLLECTION_HTTP_MAX_BODY_MB if the source really sends more.");
            }
            return new String(bytes, charsetOf(response.headers()));
        } catch (IOException e) {
            if (expired.get()) {
                throw new UpstreamException("The answer was not received within " + deadline.toSeconds() + " seconds");
            }
            throw new UpstreamException("Network error while reading the answer");
        } finally {
            timer.cancel(false);
        }
    }

    /**
     * Reads at most {@code limit} bytes (a longer answer is cut, not refused) and gives up when the whole read takes
     * longer than the deadline: the stream is closed, which ends a blocked read. For callers that wanted the head of
     * a page only (manual import); {@link #get} refuses a body that is too large instead.
     */
    public static byte[] readHead(java.io.InputStream body, int limit, Duration deadline) throws IOException {
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ScheduledFuture<?> timer = WATCHDOG.schedule(() -> {
            expired.set(true);
            closeQuietly(body);
        }, deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        try (java.io.InputStream in = body) {
            return in.readNBytes(limit);
        } catch (IOException e) {
            if (expired.get()) {
                throw new UpstreamException("The answer was not received within " + deadline.toSeconds() + " seconds");
            }
            throw e;
        } finally {
            timer.cancel(false);
        }
    }

    private static String describe(long bytes) {
        return bytes >= MB && bytes % MB == 0 ? bytes / MB + " MB" : bytes + " bytes";
    }

    private FetchResult finish(String cacheKey, HttpResponse<java.io.InputStream> response, int status) {
        String body = readBounded(response);
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
