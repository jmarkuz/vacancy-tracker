package com.vacancytracker.collection.pipeline;

import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.health.SourceHealth;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.collection.normalize.NormalizedPosting;
import com.vacancytracker.collection.normalize.Normalizer;
import com.vacancytracker.matching.MatchService;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * One collection run (T-043): every adapter is fetched, normalized, deduplicated and stored. A failing
 * adapter is recorded and skipped; the other adapters still run. Statistics are stored per adapter.
 * Locking (T-044) and expiry (T-045) are separate tasks.
 */
@Service
public class CollectionPipeline {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CollectionPipeline.class);

    public record AdapterResult(String adapter, RunRepository.SourceStats stats, String error) {
    }

    public record RunSummary(UUID runId, String status, List<AdapterResult> adapters) {
    }

    private final List<SourceAdapter> adapters;
    private final SourceHealth health;
    private final IngestService ingest;
    private final RunRepository runs;
    private final VacancyRepository vacancies;
    private final ExpiryService expiry;
    private final MatchService matching;
    private final RunEventBus events;
    private final Clock clock;
    private volatile boolean stopRequested;

    public CollectionPipeline(List<SourceAdapter> adapters, SourceHealth health, IngestService ingest,
                              RunRepository runs, VacancyRepository vacancies, ExpiryService expiry,
                              MatchService matching, RunEventBus events, Clock clock) {
        this.adapters = adapters;
        this.health = health;
        this.ingest = ingest;
        this.runs = runs;
        this.vacancies = vacancies;
        this.expiry = expiry;
        this.matching = matching;
        this.events = events;
        this.clock = clock;
    }

    /** Creates a run and executes it in the calling thread. */
    public RunSummary run(String trigger) {
        return execute(runs.start(trigger, clock.instant()));
    }

    /** Asks the running pipeline to stop before its next adapter (graceful shutdown, T-044). */
    public void requestStop() {
        stopRequested = true;
    }

    /** Executes an already created run. Stops between adapters when asked; then the run is interrupted. */
    public RunSummary execute(UUID runId) {
        return execute(runId, null);
    }

    /** scope limits the run to one adapter id; null runs all adapters. */
    public RunSummary execute(UUID runId, String scope) {
        List<AdapterResult> results = new ArrayList<>();
        long runStarted = System.nanoTime();
        MatchService.MatchSession session = matching.session();
        events.publish(runId, "started", Map.of("runId", runId.toString()));
        try {
            return executeAdapters(runId, scope, results, runStarted, session);
        } finally {
            // Reached normally, this changes nothing (only a running run can be finished). After an unexpected error
            // it keeps the run from staying "running" for ever and tells the Refresh stream that it is over.
            try {
                if (runs.finish(runId, "interrupted", clock.instant()) > 0) {
                    publishDone(runId, "interrupted", results);
                }
            } catch (DataAccessException e) {
                LOG.warn("The run could not be closed: {}", e.getClass().getSimpleName());
            }
        }
    }

    private RunSummary executeAdapters(UUID runId, String scope, List<AdapterResult> results, long runStarted,
                                       MatchService.MatchSession session) {
        for (SourceAdapter adapter : adapters) {
            if (scope != null && !scope.equals(adapter.id())) {
                continue;
            }
            if (stopRequested) {
                runs.finish(runId, "interrupted", clock.instant());
                publishDone(runId, "interrupted", results);
                return new RunSummary(runId, "interrupted", results);
            }
            events.publish(runId, "source", Map.of("adapter", adapter.id(), "state", "running"));
            long started = System.nanoTime();
            AdapterResult result = runAdapter(adapter, session);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            results.add(result);
            runs.recordStat(runId, adapter.id(), result.stats(), durationMs, result.error());
            logSource(runId, result, durationMs);
            events.publish(runId, "source", sourceEvent(result));
        }
        boolean anyFailed = results.stream().anyMatch(r -> r.error() != null);
        String status = anyFailed ? "completed_with_errors" : "completed";
        runs.finish(runId, status, clock.instant());
        try {
            expiry.expireUnseen();
        } catch (DataAccessException e) {
            LOG.warn("Expiry could not run: {}", e.getClass().getSimpleName());
        }
        publishDone(runId, status, results);
        logRun(runId, status, results, runStarted);
        return new RunSummary(runId, status, results);
    }

    /** One line per source: in the vps profile these are JSON fields, in text mode they are in the message. */
    private static void logSource(UUID runId, AdapterResult result, long durationMs) {
        RunRepository.SourceStats s = result.stats();
        var event = result.error() == null ? LOG.atInfo() : LOG.atWarn();
        event.addKeyValue("runId", runId.toString()).addKeyValue("adapter", result.adapter())
                .addKeyValue("fetched", s.fetched).addKeyValue("new", s.newCount).addKeyValue("updated", s.updated)
                .addKeyValue("duplicates", s.duplicates).addKeyValue("errors", s.errors).addKeyValue("durationMs", durationMs)
                .log("Source {} {}: fetched {}, new {}, updated {}, duplicates {}, errors {}, {} ms{}", result.adapter(),
                        result.error() == null ? "finished" : "failed", s.fetched, s.newCount, s.updated, s.duplicates, s.errors,
                        durationMs, result.error() == null ? "" : " (" + result.error() + ")");
    }

    private static void logRun(UUID runId, String status, List<AdapterResult> results, long startedNanos) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
        int fetched = results.stream().mapToInt(r -> r.stats().fetched).sum();
        int newCount = results.stream().mapToInt(r -> r.stats().newCount).sum();
        int updated = results.stream().mapToInt(r -> r.stats().updated).sum();
        int failed = (int) results.stream().filter(r -> r.error() != null).count();
        LOG.atInfo().addKeyValue("runId", runId.toString()).addKeyValue("status", status).addKeyValue("sources", results.size())
                .addKeyValue("fetched", fetched).addKeyValue("new", newCount).addKeyValue("updated", updated)
                .addKeyValue("failedSources", failed).addKeyValue("durationMs", durationMs)
                .log("Run {} {}: {} sources, fetched {}, new {}, updated {}, failed sources {}, {} ms", runId, status,
                        results.size(), fetched, newCount, updated, failed, durationMs);
    }

    private static Map<String, Object> sourceEvent(AdapterResult result) {
        RunRepository.SourceStats s = result.stats();
        return Map.of(
                "adapter", result.adapter(),
                "state", result.error() == null ? "finished" : "failed",
                "fetched", s.fetched,
                "new", s.newCount,
                "updated", s.updated,
                "duplicates", s.duplicates,
                "errors", s.errors,
                "error", result.error() == null ? "" : result.error());
    }

    private void publishDone(UUID runId, String status, List<AdapterResult> results) {
        int newCount = results.stream().mapToInt(r -> r.stats().newCount).sum();
        int updated = results.stream().mapToInt(r -> r.stats().updated).sum();
        int failed = (int) results.stream().filter(r -> r.error() != null).count();
        events.publish(runId, "done", Map.of("status", status, "new", newCount, "updated", updated,
                "failedSources", failed));
    }

    private AdapterResult runAdapter(SourceAdapter adapter, MatchService.MatchSession session) {
        RunRepository.SourceStats stats = new RunRepository.SourceStats();
        String id = adapter.id();
        if (!health.allowRequest(id)) {
            stats.errors = 1;
            return new AdapterResult(id, stats, "Circuit open: skipped until the retry window ends");
        }
        Instant now = clock.instant();
        try {
            FetchOutcome outcome = adapter.fetch();
            health.recordSuccess(id);
            if (outcome.unchanged()) {
                vacancies.touchAdapter(id, now);
            } else {
                boolean allStored = true;
                for (RawPosting raw : outcome.postings()) {
                    stats.fetched++;
                    allStored &= store(raw, stats, session);
                }
                if (allStored) {
                    try {
                        outcome.onStored().run();
                    } catch (DataAccessException e) {
                        // Not marked done, so the source offers the same items again; the de-duplication absorbs them.
                        stats.errors++;
                    }
                }
            }
            return new AdapterResult(id, stats, outcome.warning());
        } catch (UpstreamException e) {
            return failed(id, stats, e.getMessage());
        } catch (DataAccessException e) {
            return failed(id, stats, "The source could not be recorded: " + e.getClass().getSimpleName());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A misconfigured source (a bad header value, an unusable setting) must not stop the other sources.
            return failed(id, stats, "The source could not be read: " + e.getClass().getSimpleName());
        }
    }

    private AdapterResult failed(String id, RunRepository.SourceStats stats, String message) {
        health.recordFailure(id, message);
        stats.errors++;
        return new AdapterResult(id, stats, message);
    }

    /** False when the posting could not be written. A posting that is merely not valid is counted but is not retried. */
    private boolean store(RawPosting raw, RunRepository.SourceStats stats, MatchService.MatchSession session) {
        Optional<NormalizedPosting> normalized = Normalizer.normalize(raw);
        if (normalized.isEmpty()) {
            stats.errors++;
            return true;
        }
        try {
            IngestService.Ingested ingested = ingest.ingest(normalized.get(), clock.instant());
            switch (ingested.outcome()) {
                case NEW -> stats.newCount++;
                case UPDATED -> stats.updated++;
                case DUPLICATE -> stats.duplicates++;
                default -> { }
            }
            session.match(ingested.vacancyId());
            return true;
        } catch (DataAccessException | IllegalArgumentException e) {
            stats.errors++;
            return false;
        }
    }
}
