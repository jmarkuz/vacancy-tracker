package com.vacancytracker.collection.adapter;

import java.util.List;

/**
 * Result of one adapter run. unchanged means the source answered Not Modified for everything it
 * reads: the pipeline keeps previously seen vacancies as seen (ADR-018).
 */
public record FetchOutcome(List<RawPosting> postings, boolean unchanged, Runnable onStored, String warning) {

    public FetchOutcome(List<RawPosting> postings, boolean unchanged) {
        this(postings, unchanged, () -> { }, null);
    }

    public static FetchOutcome of(List<RawPosting> postings) {
        return new FetchOutcome(List.copyOf(postings), false);
    }

    public static FetchOutcome noChange() {
        return new FetchOutcome(List.of(), true);
    }

    /**
     * Work to do once the pipeline has stored every posting without a storage error, for sources whose items are
     * offered only once (an alert email): marking them done earlier would lose them if the storing failed.
     */
    public FetchOutcome afterStored(Runnable action) {
        return new FetchOutcome(postings, unchanged, action, warning);
    }

    /**
     * The postings that could be read, with a note that part of the source could not be. The run keeps what was read,
     * but the source does not count as cleanly read, so what is missing is not taken for gone (expiry, ADR-014).
     */
    public FetchOutcome withWarning(String text) {
        return new FetchOutcome(postings, unchanged, onStored, text);
    }
}
