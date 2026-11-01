package com.vacancytracker.collection.adapter;

import java.util.List;

/**
 * Result of one adapter run. unchanged means the source answered Not Modified for everything it
 * reads: the pipeline keeps previously seen vacancies as seen (ADR-018).
 */
public record FetchOutcome(List<RawPosting> postings, boolean unchanged) {

    public static FetchOutcome of(List<RawPosting> postings) {
        return new FetchOutcome(List.copyOf(postings), false);
    }

    public static FetchOutcome noChange() {
        return new FetchOutcome(List.of(), true);
    }
}
