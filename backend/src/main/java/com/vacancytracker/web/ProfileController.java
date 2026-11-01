package com.vacancytracker.web;

import com.vacancytracker.matching.ProfileInput;
import com.vacancytracker.matching.SearchProfile;
import com.vacancytracker.matching.SearchProfileService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final SearchProfileService profiles;

    public ProfileController(SearchProfileService profiles) {
        this.profiles = profiles;
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
    public ResponseEntity<SearchProfile> create(@RequestBody ProfileInput input) {
        SearchProfile created = profiles.create(input);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/profiles/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Saving always creates a new version; the previous one stays readable. */
    @PutMapping("/{id}")
    public SearchProfile update(@PathVariable UUID id, @RequestBody ProfileInput input) {
        return profiles.update(id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        profiles.delete(id);
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
        return profiles.restore(id, version);
    }
}
