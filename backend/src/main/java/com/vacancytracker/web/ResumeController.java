package com.vacancytracker.web;

import com.vacancytracker.application.fit.ResumeFit;
import com.vacancytracker.application.fit.ResumeFitService;
import com.vacancytracker.application.resume.Resume;
import com.vacancytracker.application.resume.ResumeRepository;
import com.vacancytracker.application.resume.ResumeService;
import com.vacancytracker.application.resume.ResumeTextExtractor;
import com.vacancytracker.common.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The user's resume and the fit of vacancies to it (T-080, T-081). */
@RestController
public class ResumeController {

    static final int MAX_FIT_IDS = 200;

    private final ResumeService resumes;
    private final ResumeFitService fits;

    public ResumeController(ResumeService resumes, ResumeFitService fits) {
        this.resumes = resumes;
        this.fits = fits;
    }

    /** The file is the raw request body (PDF, DOCX, TXT or MD); its name comes as a parameter. */
    @PostMapping(value = "/api/resume/file", consumes = MediaType.ALL_VALUE)
    public ResumeResponse upload(@RequestParam String fileName, HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(ResumeTextExtractor.MAX_BYTES + 1);
        if (bytes.length > ResumeTextExtractor.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than 5 MB.");
        }
        return response(resumes.upload(fileName, request.getContentType(), bytes));
    }

    @PostMapping("/api/resume/text")
    public ResumeResponse paste(@Valid @RequestBody PasteRequest request) {
        return response(resumes.paste(request.name(), request.text()));
    }

    @GetMapping("/api/resume")
    public ResumeResponse get() {
        return response(resumes.active());
    }

    @PutMapping("/api/resume/keywords")
    public ResumeResponse keywords(@Valid @RequestBody KeywordsRequest request) {
        return response(resumes.setKeywords(request.keywords()));
    }

    @DeleteMapping("/api/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        resumes.delete();
    }

    @GetMapping("/api/resume/original")
    public ResponseEntity<byte[]> original() {
        ResumeRepository.Original file = resumes.original();
        MediaType type = file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(file.contentType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName() == null ? "resume" : file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    /** Fits of the listed vacancies (comma-separated ids); empty when no resume is uploaded. */
    @GetMapping("/api/resume/fits")
    public List<ResumeFit> fits(@RequestParam String ids) {
        List<UUID> list = Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(UUID::fromString).toList();
        if (list.size() > MAX_FIT_IDS) {
            throw new IllegalArgumentException("At most " + MAX_FIT_IDS + " ids per request.");
        }
        return fits.fitsFor(list);
    }

    @GetMapping("/api/vacancies/{id}/fit")
    public ResumeFit fit(@PathVariable UUID id) {
        return fits.fitFor(id).orElseThrow(() -> new NotFoundException("Upload a resume to see how vacancies fit it."));
    }

    @PostMapping("/api/resume/recalculate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void recalculate() {
        fits.recalculateInBackground();
    }

    private ResumeResponse response(Resume r) {
        var years = resumes.years(r);
        return new ResumeResponse(r.id(), r.name(), r.fileName(), r.text(), r.keywords(), r.revision(), r.uploadedAt(),
                r.updatedAt(), years.isPresent() ? years.getAsInt() : null, r.fileName() != null);
    }

    public record PasteRequest(@Size(max = 200) String name, @NotNull @Size(max = 200_000) String text) {
    }

    public record KeywordsRequest(@NotNull @Size(max = 60) List<@Size(max = 60) String> keywords) {
    }

    public record ResumeResponse(UUID id, String name, String fileName, String text, List<String> keywords, int revision,
                                 Instant uploadedAt, Instant updatedAt, Integer years, boolean hasOriginal) {
    }
}
