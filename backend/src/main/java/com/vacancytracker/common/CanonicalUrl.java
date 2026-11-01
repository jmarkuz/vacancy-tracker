package com.vacancytracker.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical form of a vacancy URL, used as the first dedup key (ADR-013).
 * Lowercases scheme and host, drops default ports, fragments, trailing slashes
 * and tracking parameters (utm_* and a fixed list of others).
 */
public final class CanonicalUrl {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "gclid", "fbclid", "mc_cid", "mc_eid", "ref", "ref_src", "source", "trk", "trkid");

    private CanonicalUrl() {
    }

    public static Optional<String> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = port == -1 || port == 80 && "http".equals(scheme) || port == 443 && "https".equals(scheme);
            String authority = defaultPort ? host : host + ":" + port;
            String path = stripTrailingSlash(uri.getRawPath() == null ? "" : uri.getRawPath());
            String query = keptQuery(uri.getRawQuery());
            String result = scheme + "://" + authority + path + (query.isEmpty() ? "" : "?" + query);
            return Optional.of(result);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String keptQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        return java.util.Arrays.stream(rawQuery.split("&"))
                .filter(p -> !p.isEmpty())
                .filter(p -> !isTracking(p))
                .collect(Collectors.joining("&"));
    }

    private static boolean isTracking(String pair) {
        String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
        return name.startsWith("utm_") || TRACKING_PARAMS.contains(name);
    }
}
