package com.vacancytracker.web;

import com.vacancytracker.collection.pipeline.RunCoordinator;
import com.vacancytracker.collection.pipeline.RunEventBus;
import com.vacancytracker.common.NotFoundException;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.vacancytracker.collection.pipeline.RunRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.vacancytracker.collection.pipeline.RunCoordinator.Started;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunCoordinator coordinator;
    private final RunRepository runs;
    private final RunEventBus events;
    private final String cron;

    public RunController(RunCoordinator coordinator, RunRepository runs, RunEventBus events,
                         @Value("${app.collection.cron:0 0 6,18 * * *}") String cron) {
        this.coordinator = coordinator;
        this.runs = runs;
        this.events = events;
        this.cron = cron;
    }

    /** Last finished run with totals, and the next scheduled start, for the "Last refreshed" line. */
    @GetMapping("/latest")
    public LatestResponse latest() {
        Optional<RunRepository.RunRow> last = runs.findLatestFinished();
        Instant next = CronExpression.parse(cron)
                .next(ZonedDateTime.now(ZoneId.systemDefault()))
                .toInstant();
        if (last.isEmpty()) {
            return new LatestResponse(null, null, null, 0, 0, 0, next);
        }
        List<RunRepository.StatRow> stats = runs.findStats(last.get().id());
        return new LatestResponse(last.get().id(), last.get().status(), last.get().finishedAt(),
                stats.stream().mapToInt(RunRepository.StatRow::newCount).sum(),
                stats.stream().mapToInt(RunRepository.StatRow::updated).sum(),
                stats.stream().filter(s -> s.errorMessage() != null).count(),
                next);
    }

    /**
     * Progress as server-sent events: events already published, then live ones. For a run that is no
     * longer in memory, a single done event with the stored status is sent.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        RunRepository.RunRow run = runs.findRun(id).orElseThrow(() -> new NotFoundException("Run not found: " + id));
        if (events.knows(id)) {
            return events.subscribe(id);
        }
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("done").data(Map.of("status", run.status())));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 202 when a new run started; 200 with the active run when one is already running. */
    @PostMapping
    public ResponseEntity<RunResponse> trigger() {
        Started started = coordinator.start("manual");
        HttpStatus status = started.started() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new RunResponse(started.runId(), started.started(),
                runs.findRun(started.runId()).orElseThrow().status(), null, null, List.of()));
    }

    @GetMapping("/{id}")
    public RunResponse get(@PathVariable UUID id) {
        RunRepository.RunRow run = runs.findRun(id).orElseThrow(() -> new NotFoundException("Run not found: " + id));
        List<StatResponse> stats = runs.findStats(id).stream().map(StatResponse::from).toList();
        return new RunResponse(run.id(), false, run.status(), run.startedAt(), run.finishedAt(), stats);
    }

    public record LatestResponse(UUID runId, String status, Instant finishedAt, int newCount, int updated,
                                 long failedSources, Instant nextScheduledAt) {
    }

    public record RunResponse(UUID runId, boolean started, String status, Instant startedAt, Instant finishedAt,
                              List<StatResponse> sources) {
    }

    public record StatResponse(String adapter, int fetched, int newCount, int updated, int duplicates, int errors,
                               long durationMs, String errorMessage) {

        static StatResponse from(RunRepository.StatRow s) {
            return new StatResponse(s.adapter(), s.fetched(), s.newCount(), s.updated(), s.duplicates(), s.errors(),
                    s.durationMs(), s.errorMessage());
        }
    }
}
