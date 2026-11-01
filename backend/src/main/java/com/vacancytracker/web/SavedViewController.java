package com.vacancytracker.web;

import com.vacancytracker.common.SqlFragment;
import com.vacancytracker.matching.Criterion;
import com.vacancytracker.matching.SavedView;
import com.vacancytracker.matching.SavedViewService;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancySort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/views")
public class SavedViewController {

    private final SavedViewService views;
    private final VacancyService vacancies;

    public SavedViewController(SavedViewService views, VacancyService vacancies) {
        this.views = views;
        this.vacancies = vacancies;
    }

    @GetMapping
    public List<SavedViewResponse> list() {
        return views.list().stream().map(SavedViewResponse::from).toList();
    }

    @GetMapping("/{id}")
    public SavedViewResponse get(@PathVariable UUID id) {
        return SavedViewResponse.from(views.get(id));
    }

    @PostMapping
    public ResponseEntity<SavedViewResponse> create(@Valid @RequestBody SavedViewRequest request) {
        SavedView created = views.create(request.name(), request.criteria(), request.sort(), request.visibleColumns());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/views/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(SavedViewResponse.from(created));
    }

    @PutMapping("/{id}")
    public SavedViewResponse update(@PathVariable UUID id, @Valid @RequestBody SavedViewRequest request) {
        return SavedViewResponse.from(views.update(id, request.name(), request.criteria(), request.sort(),
                request.visibleColumns()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        views.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/vacancies")
    public VacancyController.PageResponse<VacancyController.VacancyResponse> apply(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and 200");
        }
        SavedView view = views.get(id);
        SqlFragment where = views.compileFor(view);
        VacancySort sort = VacancySort.parse(view.sort());
        List<VacancyController.VacancyResponse> items = vacancies.search(where, sort, page, size).stream()
                .map(VacancyController.VacancyResponse::from).toList();
        return new VacancyController.PageResponse<>(items, page, size, vacancies.countMatching(where));
    }

    public record SavedViewRequest(
            @NotBlank @Size(max = 100) String name,
            List<Criterion> criteria,
            @Size(max = 50) String sort,
            List<@Size(max = 50) String> visibleColumns) {
    }

    public record SavedViewResponse(UUID id, String name, List<Criterion> criteria, String sort, List<String> visibleColumns) {

        static SavedViewResponse from(SavedView v) {
            return new SavedViewResponse(v.id(), v.name(), v.criteria(), v.sort(), v.visibleColumns());
        }
    }
}
