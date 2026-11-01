package com.vacancytracker.web;

import com.vacancytracker.matching.RecalculationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recalculation")
public class RecalculationController {

    private final RecalculationService recalculation;

    public RecalculationController(RecalculationService recalculation) {
        this.recalculation = recalculation;
    }

    /** The "Recalculate scores" button. 202 when a job started; 200 when one was already running (queued once more). */
    @PostMapping
    public ResponseEntity<RecalculationService.Started> start() {
        RecalculationService.Started started = recalculation.start();
        return ResponseEntity.status(started.started() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(started);
    }

    /** 404 until the first job has run. */
    @GetMapping
    public RecalculationService.Progress progress() {
        return recalculation.progress();
    }
}
