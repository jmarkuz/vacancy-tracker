package com.vacancytracker.web;

import com.vacancytracker.application.packaging.ApplicationPackage;
import com.vacancytracker.application.packaging.PackageService;
import com.vacancytracker.vacancy.VacancyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Application packages (T-084) and "I submitted" (T-085). The app never submits an application itself. */
@RestController
public class PackageController {

    private final PackageService packages;
    private final VacancyService vacancies;

    public PackageController(PackageService packages, VacancyService vacancies) {
        this.packages = packages;
        this.vacancies = vacancies;
    }

    @PostMapping("/api/vacancies/{id}/packages")
    public ApplicationPackage prepare(@PathVariable UUID id, @Valid @RequestBody PrepareRequest request) {
        return packages.prepare(id, request.resumeDocumentId(), request.coverLetterDocumentId(), request.applyUrl(), request.note());
    }

    @GetMapping("/api/vacancies/{id}/packages")
    public List<ApplicationPackage> list(@PathVariable UUID id) {
        return packages.list(id);
    }

    @GetMapping("/api/packages/{packageId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID packageId) {
        ApplicationPackage p = packages.get(packageId);
        String company = vacancies.get(p.vacancyId()).getCompany().replaceAll("[^\\p{L}\\p{N}]+", "-");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("application-" + company + "-" + p.reference() + ".zip", StandardCharsets.UTF_8).build().toString())
                .body(packages.zip(packageId));
    }

    @PostMapping("/api/packages/{packageId}/submitted")
    public SubmittedResponse submitted(@PathVariable UUID packageId, @Valid @RequestBody(required = false) SubmittedRequest request) {
        PackageService.Submitted result = packages.markSubmitted(packageId, request == null ? null : request.note());
        return new SubmittedResponse(result.applicationPackage(), result.vacancy().getStatus().label());
    }

    public record PrepareRequest(UUID resumeDocumentId, UUID coverLetterDocumentId, @Size(max = 2000) String applyUrl,
                                 @Size(max = 1000) String note) {
    }

    public record SubmittedRequest(@Size(max = 1000) String note) {
    }

    public record SubmittedResponse(ApplicationPackage applicationPackage, String vacancyStatus) {
    }
}
