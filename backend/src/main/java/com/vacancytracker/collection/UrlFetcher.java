package com.vacancytracker.collection;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Fetches a single page for manual import (T-023). One request, no cookies, a custom User-Agent,
 * http(s) only and a bounded body size. Not a crawler.
 */
@Component
public class UrlFetcher {

    static final String USER_AGENT = "VacancyTracker/0.1 (personal job search; +no-crawl)";
    static final int MAX_BYTES = 2 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String fetch(String url) {
        URI uri = URI.create(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only http and https URLs can be imported");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new UrlFetchException("Source responded with HTTP " + response.statusCode());
            }
            String body = response.body();
            return body.length() > MAX_BYTES ? body.substring(0, MAX_BYTES) : body;
        } catch (IOException e) {
            throw new UrlFetchException("Could not fetch the page: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UrlFetchException("Interrupted while fetching the page");
        }
    }
}
