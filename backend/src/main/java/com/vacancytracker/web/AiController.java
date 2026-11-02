package com.vacancytracker.web;

import com.vacancytracker.application.ai.AiService;
import com.vacancytracker.application.documents.DocumentService;
import com.vacancytracker.application.documents.DocxWriter;
import com.vacancytracker.application.documents.GenerationSnapshot;
import com.vacancytracker.application.documents.TailoredDocument;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.VacancyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The AI opt-in (T-082) and the tailored documents of a vacancy (T-083, T-094). */
@RestController
public class AiController {

    private final AiService ai;
    private final DocumentService documents;
    private final VacancyService vacancies;

    public AiController(AiService ai, DocumentService documents, VacancyService vacancies) {
        this.ai = ai;
        this.documents = documents;
        this.vacancies = vacancies;
    }

    @GetMapping("/api/ai/status")
    public AiService.Status status() {
        return ai.status();
    }

    /** The owner switches AI on for the configured provider. */
    @PostMapping("/api/ai/consent")
    public AiService.Status consent() {
        return ai.consent();
    }

    @DeleteMapping("/api/ai/consent")
    public AiService.Status revoke() {
        return ai.revoke();
    }

    /** A draft from Claude adapting the preset's base draft; not stored until the owner saves it. */
    @PostMapping("/api/vacancies/{id}/documents/draft")
    public DocumentService.Draft draft(@PathVariable UUID id, @Valid @RequestBody DraftRequest request) {
        return documents.draft(id, request.kind(), request.presetId(), request.instructions());
    }

    /** AI shortens a resume that does not fit two pages, at most twice (T-097). */
    @PostMapping("/api/vacancies/{id}/documents/shorten")
    public DocumentService.Shortened shorten(@PathVariable UUID id, @Valid @RequestBody TextRequest request) {
        return documents.shorten(id, request.text());
    }

    /** The preset's base draft saved as the next version, unchanged and without AI. */
    @PostMapping("/api/vacancies/{id}/documents/as-is")
    public TailoredDocument useAsIs(@PathVariable UUID id, @Valid @RequestBody AsIsRequest request) {
        return documents.useAsIs(id, request.kind(), request.presetId());
    }

    @PostMapping("/api/vacancies/{id}/documents")
    public TailoredDocument save(@PathVariable UUID id, @Valid @RequestBody SaveRequest request) {
        return documents.save(id, request.kind(), request.content(), request.origin(), request.provider(), request.model(),
                request.snapshotId());
    }

    /** What a version was made from: the preset's goal, weights, prompt and base draft at that moment. */
    @GetMapping("/api/documents/{documentId}/snapshot")
    public GenerationSnapshot snapshot(@PathVariable UUID documentId) {
        return documents.snapshotOf(documents.get(documentId))
                .orElseThrow(() -> new NotFoundException("This version was written by hand, so it has no snapshot."));
    }

    @GetMapping("/api/vacancies/{id}/documents")
    public List<TailoredDocument> list(@PathVariable UUID id) {
        return documents.list(id);
    }

    /** One version as DOCX (default), Markdown or plain text. */
    @GetMapping("/api/documents/{documentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID documentId, @RequestParam(defaultValue = "docx") String format) {
        TailoredDocument doc = documents.get(documentId);
        String company = vacancies.get(doc.vacancyId()).getCompany();
        byte[] body;
        MediaType type;
        String extension;
        switch (format) {
            case "md" -> {
                body = doc.content().getBytes(StandardCharsets.UTF_8);
                type = MediaType.parseMediaType("text/markdown;charset=UTF-8");
                extension = "md";
            }
            case "txt" -> {
                body = doc.content().getBytes(StandardCharsets.UTF_8);
                type = MediaType.parseMediaType("text/plain;charset=UTF-8");
                extension = "txt";
            }
            case "pdf" -> {
                body = documents.pdf(doc);
                type = MediaType.APPLICATION_PDF;
                extension = "pdf";
            }
            case "docx" -> {
                body = DocxWriter.write(doc.content());
                type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                extension = "docx";
            }
            default -> throw new IllegalArgumentException("format must be pdf, docx, md or txt");
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(doc.fileName(company, extension), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    public record DraftRequest(@NotBlank String kind, UUID presetId, @Size(max = 2000) String instructions) {
    }

    public record AsIsRequest(@NotBlank String kind, UUID presetId) {
    }

    public record TextRequest(@NotBlank @Size(max = 100_000) String text) {
    }

    public record SaveRequest(@NotBlank String kind, @NotBlank @Size(max = 100_000) String content, String origin,
                              @Size(max = 50) String provider, @Size(max = 100) String model, UUID snapshotId) {
    }
}
