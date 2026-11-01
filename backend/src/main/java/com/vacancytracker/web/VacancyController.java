package com.vacancytracker.web;

import com.vacancytracker.collection.JobPostingExtractor;
import com.vacancytracker.collection.NoJobPostingException;
import com.vacancytracker.collection.UrlFetcher;
import com.fasterxml.jackson.databind.node.TextNode;
import com.vacancytracker.common.SqlFragment;
import com.vacancytracker.matching.Criterion;
import com.vacancytracker.matching.CriterionCompiler;
import com.vacancytracker.vacancy.StatusEvent;
import com.vacancytracker.vacancy.VacancySort;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancyStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/vacancies")
public class VacancyController {

    private static final int MAX_PAGE_SIZE = 200;

    private final VacancyService service;
    private final UrlFetcher urlFetcher;
    private final CriterionCompiler compiler;

    public VacancyController(VacancyService service, UrlFetcher urlFetcher, CriterionCompiler compiler) {
        this.service = service;
        this.urlFetcher = urlFetcher;
        this.compiler = compiler;
    }

    @GetMapping
    public PageResponse<VacancyResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        List<Criterion> criteria = new ArrayList<>(compiler.parse(filter));
        if (status != null && !status.isBlank()) {
            criteria.add(new Criterion(null, "status", "equals", TextNode.valueOf(status), "require", null, null, null));
        }
        SqlFragment where = compiler.compile(criteria);
        List<VacancyResponse> items = service.search(where, VacancySort.parse(sort), page, size).stream()
                .map(VacancyResponse::from).toList();
        return new PageResponse<>(items, page, size, service.countMatching(where));
    }

    @GetMapping("/{id}")
    public VacancyResponse get(@PathVariable UUID id) {
        return VacancyResponse.from(service.get(id));
    }

    @PostMapping
    public ResponseEntity<VacancyResponse> create(@Valid @RequestBody CreateRequest request) {
        Vacancy created = service.create(request.title(), request.company(), request.url(), request.description());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(VacancyResponse.from(created));
    }

    @PatchMapping("/{id}")
    public VacancyResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return VacancyResponse.from(service.update(id, new VacancyService.UpdateCommand(
                request.description(), request.notes(), request.contact(), request.priority(), request.tags(),
                request.nextActionAt(), request.nextActionNote())));
    }

    @PostMapping("/import-url")
    public ResponseEntity<VacancyResponse> importFromUrl(@Valid @RequestBody ImportUrlRequest request) {
        String html = urlFetcher.fetch(request.url());
        JobPostingExtractor.ExtractedJob job = JobPostingExtractor.extract(html)
                .orElseThrow(() -> new NoJobPostingException(request.url()));
        Vacancy created = service.createImported(request.url(), job);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/vacancies/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(VacancyResponse.from(created));
    }

    @PostMapping("/{id}/status")
    public VacancyResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return VacancyResponse.from(service.changeStatus(id, VacancyStatus.fromLabel(request.status()), request.note()));
    }

    @GetMapping("/{id}/history")
    public List<StatusEventResponse> history(@PathVariable UUID id) {
        return service.history(id).stream().map(StatusEventResponse::from).toList();
    }

    public record CreateRequest(
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 200) String company,
            @Size(max = 2000) String url,
            @Size(max = 100_000) String description) {
    }

    public record UpdateRequest(
            @Size(max = 100_000) String description,
            @Size(max = 10_000) String notes,
            @Size(max = 300) String contact,
            Integer priority,
            List<@Size(max = 50) String> tags,
            Instant nextActionAt,
            @Size(max = 1000) String nextActionNote) {
    }

    public record ImportUrlRequest(@NotBlank @Size(max = 2000) String url) {
    }

    public record StatusRequest(@NotBlank String status, @Size(max = 2000) String note) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }

    public record VacancyResponse(
            UUID id, String title, String company, String url, String canonicalUrl, String location, String country, String workMode,
            String description,
            java.math.BigDecimal salaryMin, java.math.BigDecimal salaryMax, String currency, String salaryPeriod,
            String status, Integer priority, String notes, String contact, String nextActionNote, Instant nextActionAt,
            List<String> tags, Set<String> userEditedFields, Instant firstSeenAt, Instant lastSeenAt, boolean isNew) {

        static VacancyResponse from(Vacancy v) {
            return new VacancyResponse(v.getId(), v.getTitle(), v.getCompany(), v.getUrl(), v.getCanonicalUrl(),
                    v.getLocation(), v.getCountry(), v.getWorkMode(), v.getDescription(), v.getSalaryMin(), v.getSalaryMax(), v.getCurrency(), v.getSalaryPeriod(),
                    v.getStatus().label(), v.getPriority(), v.getNotes(), v.getContact(), v.getNextActionNote(),
                    v.getNextActionAt(), v.getTags(), v.getUserEditedFields(), v.getFirstSeenAt(), v.getLastSeenAt(),
                    v.isNew());
        }
    }

    public record StatusEventResponse(String from, String to, Instant at, String note) {

        static StatusEventResponse from(StatusEvent e) {
            return new StatusEventResponse(e.from() == null ? null : e.from().label(), e.to().label(), e.at(), e.note());
        }
    }
}
