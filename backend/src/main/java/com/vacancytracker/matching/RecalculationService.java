package com.vacancytracker.matching;

import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.VacancyRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Background recalculation of every vacancy's matches (T-053). It runs after a profile changes, or on request.
 * One job at a time: a request during a job is remembered and run once more when the job ends, so an edit made
 * mid-job is never lost. Recalculation only writes matches and the automatic inbox rule (ADR-026); statuses the
 * user set are never touched. Progress is kept in memory: it describes the current or the last job.
 */
@Service
public class RecalculationService {

    public enum State { RUNNING, COMPLETED }

    public record Progress(UUID jobId, State state, int processed, int total, int failed, Instant startedAt,
                           Instant finishedAt) {
    }

    /** started is false when a job was already running; the request is then queued for one more pass. */
    public record Started(UUID jobId, boolean started) {
    }

    private static final class Job {
        final UUID id = UUID.randomUUID();
        final Instant startedAt;
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        volatile int total;
        volatile Instant finishedAt;

        Job(Instant startedAt) {
            this.startedAt = startedAt;
        }

        Progress snapshot() {
            return new Progress(id, finishedAt == null ? State.RUNNING : State.COMPLETED, processed.get(), total,
                    failed.get(), startedAt, finishedAt);
        }
    }

    private final MatchService matching;
    private final VacancyRepository vacancies;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "recalculation");
        t.setDaemon(true);
        return t;
    });
    private Job current;
    private boolean rerunRequested;

    public RecalculationService(MatchService matching, VacancyRepository vacancies, Clock clock) {
        this.matching = matching;
        this.vacancies = vacancies;
        this.clock = clock;
    }

    public synchronized Started start() {
        if (current != null && current.finishedAt == null) {
            rerunRequested = true;
            return new Started(current.id, false);
        }
        Job job = new Job(clock.instant());
        current = job;
        executor.submit(() -> run(job));
        return new Started(job.id, true);
    }

    /** Progress of the running job, or of the last finished one. */
    public synchronized Progress progress() {
        if (current == null) {
            throw new NotFoundException("No recalculation has run yet");
        }
        return current.snapshot();
    }

    private void run(Job job) {
        MatchService.MatchSession session = matching.session();
        List<UUID> ids = vacancies.findAllIds();
        job.total = ids.size();
        for (UUID id : ids) {
            try {
                session.match(id);
            } catch (DataAccessException | IllegalArgumentException | NotFoundException e) {
                job.failed.incrementAndGet();
            }
            job.processed.incrementAndGet();
        }
        finish(job);
    }

    private synchronized void finish(Job job) {
        job.finishedAt = clock.instant();
        if (rerunRequested) {
            rerunRequested = false;
            Job next = new Job(clock.instant());
            current = next;
            executor.submit(() -> run(next));
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
