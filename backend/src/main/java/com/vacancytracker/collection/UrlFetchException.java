package com.vacancytracker.collection;

/** The source page could not be fetched; reported as 502 to the client. */
public class UrlFetchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UrlFetchException(String message) {
        super(message);
    }
}
