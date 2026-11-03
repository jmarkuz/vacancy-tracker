package com.vacancytracker.web;

import com.vacancytracker.application.preset.Preset;
import com.vacancytracker.application.preset.PresetDefaults;
import com.vacancytracker.application.preset.PresetService;
import com.vacancytracker.application.resume.ResumeTextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** AI presets (T-093): list, edit, duplicate, delete, choose the default, and the base documents. */
@RestController
@RequestMapping("/api/presets")
public class PresetController {

    private final PresetService presets;

    public PresetController(PresetService presets) {
        this.presets = presets;
    }

    @GetMapping
    public List<PresetResponse> list() {
        return presets.list().stream().map(this::response).toList();
    }

    @GetMapping("/{id}")
    public PresetResponse get(@PathVariable UUID id) {
        return response(presets.get(id));
    }

    /** The texts "Reset to default" puts back. */
    @GetMapping("/defaults")
    public DefaultsResponse defaults() {
        PresetDefaults d = presets.defaults();
        return new DefaultsResponse(d.mainGoal(), d.weights(), d.resumePrompt(), d.coverLetterPrompt(), d.baseResume(),
                d.baseCoverLetter(), d.guardrail());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PresetResponse create(@Valid @RequestBody NameRequest request) {
        return response(presets.create(request.name()));
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public PresetResponse duplicate(@PathVariable UUID id, @Valid @RequestBody(required = false) NameRequest request) {
        return response(presets.duplicate(id, request == null ? null : request.name()));
    }

    @PatchMapping("/{id}")
    public PresetResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return response(presets.update(id, new PresetService.Update(r.name(), r.mainGoal(), r.weights() == null ? null
                : r.weights().stream().map(w -> new Preset.Weight(w.name(), w.weight() == null ? 0 : w.weight())).toList(),
                r.resumePrompt(), r.coverLetterPrompt(), r.baseCoverLetter())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        presets.delete(id);
    }

    @PostMapping("/{id}/default")
    public PresetResponse makeDefault(@PathVariable UUID id) {
        return response(presets.makeDefault(id));
    }

    /** The file is the raw request body (PDF, DOCX, TXT or MD); its name comes as a parameter. */
    @PostMapping(value = "/{id}/base-resume/file", consumes = MediaType.ALL_VALUE)
    public PresetResponse uploadBaseResume(@PathVariable UUID id, @RequestParam String fileName, HttpServletRequest request)
            throws IOException {
        return response(presets.uploadBaseResume(id, fileName, request.getContentType(), body(request)));
    }

    @PostMapping("/{id}/base-resume/text")
    public PresetResponse pasteBaseResume(@PathVariable UUID id, @Valid @RequestBody TextRequest request) {
        return response(presets.pasteBaseResume(id, request.text()));
    }

    @DeleteMapping("/{id}/base-resume")
    public PresetResponse resetBaseResume(@PathVariable UUID id) {
        return response(presets.resetBaseResume(id));
    }

    @PostMapping(value = "/{id}/base-cover-letter/file", consumes = MediaType.ALL_VALUE)
    public PresetResponse uploadBaseCoverLetter(@PathVariable UUID id, @RequestParam String fileName, HttpServletRequest request)
            throws IOException {
        return response(presets.uploadBaseCoverLetter(id, fileName, body(request)));
    }

    private static byte[] body(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(ResumeTextExtractor.MAX_BYTES + 1);
        if (bytes.length > ResumeTextExtractor.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than 5 MB.");
        }
        return bytes;
    }

    private PresetResponse response(Preset p) {
        PresetService.Details d = presets.details(p);
        List<WeightResponse> weights = new java.util.ArrayList<>();
        for (int i = 0; i < p.weights().size(); i++) {
            Preset.Weight w = p.weights().get(i);
            weights.add(new WeightResponse(w.name(), w.weight(), Math.round(d.shares().get(i) * 1000) / 10.0));
        }
        return new PresetResponse(p.id(), p.name(), p.isDefault(), p.mainGoal(), weights, p.resumePrompt(), p.coverLetterPrompt(),
                d.baseResume(), p.baseCoverLetter(), p.baseCoverLetterFileName(), d.readiness(), p.createdAt(), p.updatedAt());
    }

    public record NameRequest(@NotNull @Size(max = 100) String name) {
    }

    public record TextRequest(@NotNull @Size(max = 200_000) String text) {
    }

    public record WeightRequest(@Size(max = 80) String name, Integer weight) {
    }

    public record UpdateRequest(@Size(max = 100) String name, @Size(max = 50_000) String mainGoal,
                                @Size(max = 20) List<@Valid WeightRequest> weights, @Size(max = 50_000) String resumePrompt,
                                @Size(max = 50_000) String coverLetterPrompt, @Size(max = 50_000) String baseCoverLetter) {
    }

    /** share is the parameter's actual percentage of the total weight, to one decimal. */
    public record WeightResponse(String name, int weight, double share) {
    }

    public record PresetResponse(UUID id, String name, boolean isDefault, String mainGoal, List<WeightResponse> weights,
                                 String resumePrompt, String coverLetterPrompt, PresetService.BaseResume baseResume,
                                 String baseCoverLetter, String baseCoverLetterFileName, PresetService.Readiness readiness,
                                 Instant createdAt, Instant updatedAt) {
    }

    public record DefaultsResponse(String mainGoal, List<Preset.Weight> weights, String resumePrompt, String coverLetterPrompt,
                                   String baseResume, String baseCoverLetter, String guardrail) {
    }
}
