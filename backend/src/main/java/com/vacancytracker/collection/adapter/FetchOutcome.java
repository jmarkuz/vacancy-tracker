package com.vacancytracker.collection.adapter;

import java.util.List;

/**
 * Result of one adapter run. unchanged means the source answered Not Modified for everything it
 * reads: the pipeline keeps previously seen vacancies as seen (ADR-018).
 */
public record FetchOutcome(List<RawPosting> postings, boolean unchanged, Runnable onStored, String warning,
                           List<String> failedBoards) {

    public FetchOutcome {
        failedBoards = failedBoards == null ? List.of() : List.copyOf(failedBoards);
    }

    public FetchOutcome(List<RawPosting> postings, boolean unchanged, Runnable onStored, String warning) {
        this(postings, unchanged, onStored, warning, List.of());
    }

    public FetchOutcome(List<RawPosting> postings, boolean unchanged) {
        this(postings, unchanged, () -> { }, null, List.of());
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
        return new FetchOutcome(postings, unchanged, action, warning, failedBoards);
    }

    /**
     * The postings that could be read, with a note that part of the source could not be. The run keeps what was read,
     * but the source does not count as cleanly read, so what is missing is not taken for gone (expiry, ADR-014).
     */
    public FetchOutcome withWarning(String text) {
        return new FetchOutcome(postings, unchanged, onStored, text, failedBoards);
    }

    /**
     * The boards of this source that could not be read in this run, for sources that read several (Greenhouse, Lever,
     * Ashby). A vacancy of any other board of the source was still looked for, so the run counts as clean for it; the run
     * does not count for the vacancies of a board named here (ADR-014).
     */
    public FetchOutcome withFailedBoards(List<String> boards) {
        return new FetchOutcome(postings, unchanged, onStored, warning, boards);
    }
}
