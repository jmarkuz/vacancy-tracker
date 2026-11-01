package com.vacancytracker.collection.adapter.html;

import java.net.URI;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One career site read by the generic HTML adapter (T-073, docs/sources.md Tier 3). Nothing is read unless the site
 * is enabled and has a recorded terms check: the date it was done and the page whose terms were read.
 *
 * @param id              short name, used in the vacancy's external id
 * @param listUrl         the list page; {page} is replaced by 1, 2, ... when maxPages is above 1
 * @param termsCheckedOn  date (yyyy-mm-dd) of the terms check recorded in docs/sources.md
 * @param termsUrl        the terms page that was read
 * @param item            CSS selector for one vacancy card
 * @param title           CSS selector, inside the card, for the title; its text is the title
 * @param link            CSS selector, inside the card, for the link to the vacancy (default: the title element or any a[href])
 * @param company         CSS selector for the company, or empty to use companyName
 * @param companyName     fixed company name when the site is one company's own career page
 * @param location        CSS selector for the place, or empty
 */
public record HtmlSite(
        String id,
        @DefaultValue("false") boolean enabled,
        String listUrl,
        @DefaultValue("1") int maxPages,
        String termsCheckedOn,
        String termsUrl,
        String item,
        String title,
        @DefaultValue("") String link,
        @DefaultValue("") String company,
        @DefaultValue("") String companyName,
        @DefaultValue("") String location) {

    public static final int MAX_PAGES_LIMIT = 5;

    /** Enabled, with a recorded terms check and everything needed to read the page. */
    public boolean approved() {
        return enabled && notBlank(id) && notBlank(listUrl) && notBlank(item) && notBlank(title)
                && notBlank(termsCheckedOn) && notBlank(termsUrl) && validListUrl();
    }

    private boolean validListUrl() {
        try {
            URI uri = URI.create(listUrl.replace("{page}", "1"));
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    int pages() {
        return listUrl != null && listUrl.contains("{page}") ? Math.max(1, Math.min(maxPages, MAX_PAGES_LIMIT)) : 1;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
