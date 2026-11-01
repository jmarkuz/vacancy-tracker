package com.vacancytracker.collection.adapter;

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
}
