package com.vacancytracker.collection.http;

/**
 * Outcome of one GET. notModified means the source answered 304 to our validators: the body is empty
 * and the caller must treat the previous data as still current.
 */
public record FetchResult(int status, String body, String etag, String lastModified, boolean notModified) {

    public static FetchResult unchanged() {
        return new FetchResult(304, "", null, null, true);
    }

    public boolean ok() {
        return notModified || status / 100 == 2;
    }
}
