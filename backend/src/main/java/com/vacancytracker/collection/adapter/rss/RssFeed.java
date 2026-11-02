package com.vacancytracker.collection.adapter.rss;

import java.net.URI;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One RSS or Atom feed read by the generic feed adapter (T-040). Nothing is read unless the feed is enabled and has
 * a recorded terms check: the date it was done and the page whose terms were read (docs/sources.md).
 *
 * @param id             short name, used in the vacancy's external id
 * @param url            the feed address
 * @param termsCheckedOn date (yyyy-mm-dd) of the terms check
 * @param termsUrl       the terms page that was read
 * @param companyName    fixed company for single-company feeds; empty to use the item's author
 */
public record RssFeed(
        String id,
        @DefaultValue("false") boolean enabled,
        String url,
        String termsCheckedOn,
        String termsUrl,
        @DefaultValue("") String companyName) {

    public boolean approved() {
        return enabled && notBlank(id) && notBlank(url) && notBlank(termsCheckedOn) && notBlank(termsUrl) && validUrl();
    }

    private boolean validUrl() {
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
