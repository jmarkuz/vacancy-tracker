package com.vacancytracker.collection.adapter;

import java.util.Optional;
import java.util.Set;

/**
 * Contract for every source (T-030). An adapter reads its configured targets, maps them to RawPosting,
 * and throws {@link com.vacancytracker.collection.http.UpstreamException} when the source fails.
 * Failures are isolated per adapter by the pipeline.
 */
public interface SourceAdapter {

    /** Stable identifier, stored with each vacancy's source attribution. */
    String id();

    /** Criteria fields this adapter can apply on the server side. Everything else is filtered after fetching. */
    Set<String> serverSideFields();

    FetchOutcome fetch();

    /**
     * For sources that follow companies the owner lists (ATS boards): the companies still followed, in lower case.
     * A vacancy of a company that is no longer listed is not expired for having gone missing, because the source
     * stopped looking for it (T-092, F8). Empty means the source does not work from such a list.
     */
    default Optional<Set<String>> followedCompanies() {
        return Optional.empty();
    }
}
