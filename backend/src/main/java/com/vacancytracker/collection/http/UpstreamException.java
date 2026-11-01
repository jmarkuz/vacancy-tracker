package com.vacancytracker.collection.http;

/** A source could not be reached or kept failing after retries. The adapter boundary records it. */
public class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The message is stored, shown in the UI and logged, so secrets in it (an address with a key) are masked. */
    public UpstreamException(String message) {
        super(com.vacancytracker.common.LogMasking.mask(message));
    }
}
