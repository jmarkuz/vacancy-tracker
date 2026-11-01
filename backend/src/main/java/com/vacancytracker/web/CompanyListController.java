package com.vacancytracker.web;

import com.vacancytracker.matching.CompanyListEntry;
import com.vacancytracker.matching.CompanyListService;
import com.vacancytracker.matching.RecalculationService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Company allowlist and blocklist, global or per profile (T-059, ADR-028). A change rescores in the background. */
@RestController
@RequestMapping("/api/company-lists")
public class CompanyListController {

    /** listType is allowlist or blocklist; profileId null means the entry applies to every profile. */
    public record NewEntry(String company, String listType, UUID profileId) {
    }

    private final CompanyListService lists;
    private final RecalculationService recalculation;
    private final boolean recalculateOnSave;

    public CompanyListController(CompanyListService lists, RecalculationService recalculation,
                                 @Value("${app.matching.recalculate-on-save:true}") boolean recalculateOnSave) {
        this.lists = lists;
        this.recalculation = recalculation;
        this.recalculateOnSave = recalculateOnSave;
    }

    @GetMapping
    public List<CompanyListEntry> list() {
        return lists.list();
    }

    @PostMapping
    public ResponseEntity<CompanyListEntry> add(@RequestBody NewEntry body) {
        CompanyListEntry entry = lists.add(body.company(), body.listType(), body.profileId());
        recalculate();
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        lists.remove(id);
        recalculate();
        return ResponseEntity.noContent().build();
    }

    private void recalculate() {
        if (recalculateOnSave) {
            recalculation.start();
        }
    }
}
