package com.vacancytracker.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.vacancytracker.matching.ProfileInput;
import com.vacancytracker.matching.ProfileValidationException;
import com.vacancytracker.matching.ProfileValidator;
import com.vacancytracker.matching.RecalculationService;
import com.vacancytracker.matching.SearchProfile;
import com.vacancytracker.matching.SearchProfileService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final SearchProfileService profiles;
    private final RecalculationService recalculation;
    private final ProfileInputParser parser;
    private final ProfileValidator validator;
    private final boolean recalculateOnSave;

    public ProfileController(SearchProfileService profiles, RecalculationService recalculation,
                             ProfileInputParser parser, ProfileValidator validator,
                             @Value("${app.matching.recalculate-on-save:true}") boolean recalculateOnSave) {
        this.profiles = profiles;
        this.recalculation = recalculation;
        this.parser = parser;
        this.validator = validator;
        this.recalculateOnSave = recalculateOnSave;
    }

    /** Fields, operators with their value shapes, modes and policies: what the rule builder may offer. */
    @GetMapping("/meta")
    public ProfileValidator.Catalog meta() {
        return ProfileValidator.catalog();
    }

    /** Dry run for live validation: 200 either way, with the normalized profile or every problem found. */
    @PostMapping("/validate")
    public ValidationResult validate(@RequestBody JsonNode body) {
        try {
            return new ValidationResult(true, validator.validate(parser.parse(body)), Map.of());
        } catch (ProfileValidationException e) {
            return new ValidationResult(false, null, e.errors());
        }
    }

    public record ValidationResult(boolean valid, ProfileInput profile, Map<String, String> errors) {
    }

    /** Saving a profile version starts a background recalculation (docs/search-profiles.md section 8). */
    private void recalculate() {
        if (recalculateOnSave) {
            recalculation.start();
        }
    }

    @GetMapping
    public List<SearchProfile> list() {
        return profiles.list();
    }

    @GetMapping("/{id}")
    public SearchProfile get(@PathVariable UUID id) {
        return profiles.get(id);
    }

    @PostMapping
    public ResponseEntity<SearchProfile> create(@RequestBody JsonNode body) {
        SearchProfile created = profiles.create(parser.parse(body));
        recalculate();
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/profiles/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Saving always creates a new version; the previous one stays readable. */
    @PutMapping("/{id}")
    public SearchProfile update(@PathVariable UUID id, @RequestBody JsonNode body) {
        SearchProfile updated = profiles.update(id, parser.parse(body));
        recalculate();
        return updated;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        profiles.delete(id);
        recalculate();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/versions")
    public List<SearchProfileService.VersionSummary> versions(@PathVariable UUID id) {
        return profiles.versions(id);
    }

    @GetMapping("/{id}/versions/{version}")
    public SearchProfile version(@PathVariable UUID id, @PathVariable int version) {
        return profiles.version(id, version);
    }

    @PostMapping("/{id}/versions/{version}/restore")
    public SearchProfile restore(@PathVariable UUID id, @PathVariable int version) {
        SearchProfile restored = profiles.restore(id, version);
        recalculate();
        return restored;
    }
}
