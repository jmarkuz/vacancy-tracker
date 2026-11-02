package com.vacancytracker.application.fit;

import com.vacancytracker.application.resume.Resume;
import com.vacancytracker.application.resume.ResumeService;
import com.vacancytracker.collection.pipeline.RunEventBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the fit of every vacancy to the active resume current (T-081, ADR-040). A resume change and the end of each
 * collection run start a background pass over the vacancies whose fit is missing or was computed for an older
 * revision. Reading a fit computes it first when it is stale, so the answer is never older than the resume.
 */
@Service
public class ResumeFitService {

    private static final Logger LOG = LoggerFactory.getLogger(ResumeFitService.class);
    static final int BATCH = 500;

    private final ResumeService resumes;
    private final ResumeFitRepository repository;
    private final RunEventBus runEvents;
    private final Clock clock;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "resume-fit");
        t.setDaemon(true);
        return t;
    });

    public ResumeFitService(ResumeService resumes, ResumeFitRepository repository, RunEventBus runEvents, Clock clock) {
        this.resumes = resumes;
        this.repository = repository;
        this.runEvents = runEvents;
        this.clock = clock;
    }

    @PostConstruct
    void listen() {
        resumes.addChangeListener(resume -> {
            if (resume != null) {
                recalculateInBackground();
            }
        });
        runEvents.addDoneListener(runId -> recalculateInBackground());
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** Starts a background pass unless one is already waiting to start. */
    public void recalculateInBackground() {
        if (queued.compareAndSet(false, true)) {
            executor.execute(() -> {
                queued.set(false);
                try {
                    int done = recalculateStale();
                    if (done > 0) {
                        LOG.info("Resume fit computed for {} vacancies", done);
                    }
                } catch (org.springframework.dao.DataAccessException e) {
                    LOG.warn("Resume fit pass failed: {}", e.getMessage());
                }
            });
        }
    }

    /** Computes every missing or stale fit now. Returns how many were computed. */
    public int recalculateStale() {
        Optional<Resume> active = resumes.findActive();
        if (active.isEmpty()) {
            return 0;
        }
        Resume resume = active.get();
        OptionalInt years = resumes.years(resume);
        int total = 0;
        List<ResumeFitRepository.VacancyText> batch;
        do {
            batch = repository.stale(resume.id(), resume.revision(), BATCH);
            for (ResumeFitRepository.VacancyText v : batch) {
                repository.upsert(compute(resume, years, v));
            }
            total += batch.size();
        } while (batch.size() == BATCH && resumes.findActive().map(r -> r.revision() == resume.revision()
                && r.id().equals(resume.id())).orElse(false));
        return total;
    }

    /** The fit of one vacancy, computed now when missing or stale. Empty when there is no resume. */
    public Optional<ResumeFit> fitFor(UUID vacancyId) {
        return fitsFor(List.of(vacancyId)).stream().findFirst();
    }

    /** Fits of the given vacancies (those that exist), each current for the active resume. */
    public List<ResumeFit> fitsFor(Collection<UUID> vacancyIds) {
        Optional<Resume> active = resumes.findActive();
        if (active.isEmpty() || vacancyIds.isEmpty()) {
            return List.of();
        }
        Resume resume = active.get();
        Map<UUID, ResumeFit> stored = new HashMap<>();
        repository.findAll(vacancyIds).forEach(f -> stored.put(f.vacancyId(), f));
        OptionalInt years = resumes.years(resume);
        List<ResumeFit> result = new ArrayList<>();
        for (UUID id : vacancyIds) {
            ResumeFit fit = stored.get(id);
            if (fit == null || !fit.resumeId().equals(resume.id()) || fit.resumeRevision() != resume.revision()) {
                Optional<ResumeFitRepository.VacancyText> vacancy = repository.vacancy(id);
                if (vacancy.isEmpty()) {
                    continue;
                }
                fit = compute(resume, years, vacancy.get());
                repository.upsert(fit);
            }
            result.add(fit);
        }
        return result;
    }

    private ResumeFit compute(Resume resume, OptionalInt years, ResumeFitRepository.VacancyText v) {
        ResumeFitScorer.Result r = ResumeFitScorer.score(resume.keywords(), years, resume.text(), v.title(), v.description());
        return new ResumeFit(v.id(), resume.id(), resume.revision(), r.score(), r.matched(), r.missing(), r.reasons(), clock.instant());
    }
}
