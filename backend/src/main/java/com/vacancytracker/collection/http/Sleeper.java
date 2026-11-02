package com.vacancytracker.collection.http;

/** Waiting is behind an interface so retry and rate-limit tests do not sleep. */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis);

    Sleeper THREAD = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Interrupted while waiting");
        }
    };
}
