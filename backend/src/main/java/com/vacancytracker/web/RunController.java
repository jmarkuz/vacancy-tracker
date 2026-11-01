package com.vacancytracker.web;

import com.vacancytracker.collection.pipeline.RunCoordinator;
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
import com.vacancytracker.common.NotFoundException;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunCoordinator coordinator;
    private final RunRepository runs;

    public RunController(RunCoordinator coordinator, RunRepository runs) {
        this.coordinator = coordinator;
        this.runs = runs;
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
