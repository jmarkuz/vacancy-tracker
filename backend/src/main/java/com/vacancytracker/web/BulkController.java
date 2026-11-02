package com.vacancytracker.web;

import com.vacancytracker.vacancy.BulkStatusService;
import com.vacancytracker.vacancy.VacancyStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bulk status change and its undo (T-063). */
@RestController
@RequestMapping("/api/bulk/vacancies")
public class BulkController {

    public record StatusRequest(@NotEmpty @Size(max = BulkStatusService.MAX_BATCH) List<UUID> ids,
                                @NotBlank String status, @Size(max = 2000) String note) {
    }

    public record UndoRequest(@NotEmpty @Size(max = BulkStatusService.MAX_BATCH) List<@Valid UndoEntry> items) {
    }

    public record UndoEntry(@jakarta.validation.constraints.NotNull UUID id, @NotBlank String status, @NotBlank String expected,
                            boolean wasNew) {
    }

    private final BulkStatusService bulk;

    public BulkController(BulkStatusService bulk) {
        this.bulk = bulk;
    }

    @PostMapping("/status")
    public BulkStatusService.Result status(@Valid @RequestBody StatusRequest request) {
        return bulk.change(request.ids(), VacancyStatus.fromLabel(request.status()), request.note());
    }

    @PostMapping("/undo")
    public BulkStatusService.Result undo(@Valid @RequestBody UndoRequest request) {
        return bulk.undo(request.items().stream()
                .map(e -> new BulkStatusService.UndoItem(e.id(), e.status(), e.expected(), e.wasNew())).toList());
    }
}
