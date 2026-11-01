package com.vacancytracker.collection.http;

/** A source could not be reached or kept failing after retries. The adapter boundary records it. */
public class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UpstreamException(String message) {
        super(message);
    }
}
