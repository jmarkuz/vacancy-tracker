package com.vacancytracker.collection;

import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches a single page for manual import (T-023). One request, no cookies, a custom User-Agent,
 * http(s) only and a bounded body size. Not a crawler.
 *
 * <p>The address is typed by the user (or by anyone holding the access token), so the server must not be a way to
 * reach its own network: loopback, private, link-local and similar addresses are refused, for every redirect hop too.
 * The check resolves the name before the request, so a name that changes its answer in between is not covered;
 * {@code app.import.allow-private-hosts=true} switches the check off for a machine where that is the point.
 */
@Component
public class UrlFetcher {

    static final String USER_AGENT = "VacancyTracker/0.1 (personal job search; +no-crawl)";
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_REDIRECTS = 3;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final boolean allowPrivateHosts;
    private final Duration bodyDeadline;

    @Autowired
    public UrlFetcher(@Value("${app.import.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this(allowPrivateHosts, HttpFetcher.BODY_DEADLINE);
    }

    UrlFetcher(boolean allowPrivateHosts, Duration bodyDeadline) {
        this.allowPrivateHosts = allowPrivateHosts;
        this.bodyDeadline = bodyDeadline;
    }

    public String fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The address is not valid");
        }
        for (int hop = 0; ; hop++) {
            requireAllowed(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html")
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    response.body().close();
                    if (hop >= MAX_REDIRECTS) {
                        throw new UrlFetchException("Too many redirects");
                    }
                    uri = redirectTarget(uri, response);
                    continue;
                }
                if (status / 100 != 2) {
                    response.body().close();
                    throw new UrlFetchException("Source responded with HTTP " + status);
                }
                return readBounded(response);
            } catch (IOException e) {
                throw new UrlFetchException("Could not fetch the page");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UrlFetchException("Interrupted while fetching the page");
            }
        }
    }

    private static URI redirectTarget(URI from, HttpResponse<?> response) {
        String location = response.headers().firstValue("Location").orElse("");
        try {
            return from.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new UrlFetchException("The page redirected to an address that is not valid");
        }
    }

    /**
     * Reads at most MAX_BYTES; more than that is cut, as the extractor needs only the head of a page. A page that
     * stalls half way is given up after the body deadline, so the request does not hang for ever (T-092, F7).
     */
    private String readBounded(HttpResponse<InputStream> response) throws IOException {
        try {
            byte[] bytes = HttpFetcher.readHead(response.body(), MAX_BYTES, bodyDeadline);
            return new String(bytes, HttpFetcher.charsetOf(response.headers()));
        } catch (UpstreamException e) {
            throw new UrlFetchException("The page did not arrive in time");
        }
    }

    private void requireAllowed(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only http and https URLs can be imported");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("The address has no host");
        }
        if (allowPrivateHosts) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isInternal(address)) {
                    throw new IllegalArgumentException("Addresses inside a private network cannot be imported");
                }
            }
        } catch (UnknownHostException e) {
            throw new UrlFetchException("The host could not be found");
        }
    }

    static boolean isInternal(InetAddress a) {
        byte[] b = a.getAddress();
        boolean uniqueLocalV6 = b.length == 16 && (b[0] & 0xFE) == 0xFC;
        boolean carrierGradeNat = b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress() || uniqueLocalV6 || carrierGradeNat;
    }
}
