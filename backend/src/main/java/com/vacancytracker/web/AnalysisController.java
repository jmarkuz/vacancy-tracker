package com.vacancytracker.web;

import com.vacancytracker.application.analysis.AnalysisService;
import com.vacancytracker.application.analysis.EmployerMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Employer match and Goal match of a vacancy (T-095). GET never calls AI; POST computes what is missing when AI is on. */
@RestController
public class AnalysisController {

    private final AnalysisService analysis;

    public AnalysisController(AnalysisService analysis) {
        this.analysis = analysis;
    }

    @GetMapping("/api/vacancies/{id}/analysis")
    public AnalysisService.Analysis read(@PathVariable UUID id, @RequestParam(required = false) UUID presetId) {
        return analysis.read(id, presetId);
    }

    @PostMapping("/api/vacancies/{id}/analysis")
    public AnalysisService.Analysis run(@PathVariable UUID id, @RequestParam(required = false) UUID presetId) {
        return analysis.run(id, presetId);
    }

    /** The Employer match of a text that is not saved yet, such as an AI draft or an edit. */
    @PostMapping("/api/vacancies/{id}/match")
    public EmployerMatch match(@PathVariable UUID id, @Valid @RequestBody MatchRequest request) {
        return analysis.matchText(id, request.text());
    }

    public record MatchRequest(@NotBlank @Size(max = 100_000) String text) {
    }
}
