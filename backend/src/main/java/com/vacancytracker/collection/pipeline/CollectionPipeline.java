package com.vacancytracker.collection.pipeline;

import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.health.SourceHealth;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.collection.normalize.NormalizedPosting;
import com.vacancytracker.collection.normalize.Normalizer;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * One collection run (T-043): every adapter is fetched, normalized, deduplicated and stored. A failing
 * adapter is recorded and skipped; the other adapters still run. Statistics are stored per adapter.
 * Locking (T-044) and expiry (T-045) are separate tasks.
 */
@Service
public class CollectionPipeline {

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
    private final Clock clock;
    private volatile boolean stopRequested;

    public CollectionPipeline(List<SourceAdapter> adapters, SourceHealth health, IngestService ingest,
                              RunRepository runs, VacancyRepository vacancies, ExpiryService expiry, Clock clock) {
        this.adapters = adapters;
        this.health = health;
        this.ingest = ingest;
        this.runs = runs;
        this.vacancies = vacancies;
        this.expiry = expiry;
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
        List<AdapterResult> results = new ArrayList<>();
        boolean anyFailed = false;
        for (SourceAdapter adapter : adapters) {
            if (stopRequested) {
                runs.finish(runId, "interrupted", clock.instant());
                return new RunSummary(runId, "interrupted", results);
            }
            long started = System.nanoTime();
            AdapterResult result = runAdapter(adapter);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            results.add(result);
            anyFailed |= result.error() != null;
            runs.recordStat(runId, adapter.id(), result.stats(), durationMs, result.error());
        }
        String status = anyFailed ? "completed_with_errors" : "completed";
        runs.finish(runId, status, clock.instant());
        expiry.expireUnseen();
        return new RunSummary(runId, status, results);
    }

    private AdapterResult runAdapter(SourceAdapter adapter) {
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
                for (RawPosting raw : outcome.postings()) {
                    stats.fetched++;
                    store(raw, stats);
                }
            }
            return new AdapterResult(id, stats, null);
        } catch (UpstreamException e) {
            health.recordFailure(id, e.getMessage());
            stats.errors++;
            return new AdapterResult(id, stats, e.getMessage());
        }
    }

    private void store(RawPosting raw, RunRepository.SourceStats stats) {
        Optional<NormalizedPosting> normalized = Normalizer.normalize(raw);
        if (normalized.isEmpty()) {
            stats.errors++;
            return;
        }
        try {
            switch (ingest.ingest(normalized.get(), clock.instant())) {
                case NEW -> stats.newCount++;
                case UPDATED -> stats.updated++;
                case DUPLICATE -> stats.duplicates++;
                default -> { }
            }
        } catch (DataAccessException | IllegalArgumentException e) {
            stats.errors++;
        }
    }
}
