package com.vacancytracker.application.ai;

/** AI is off: not configured, or the owner has not switched it on in the app. Nothing was sent. */
public class AiUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AiUnavailableException(String message) {
        super(message);
    }
}
