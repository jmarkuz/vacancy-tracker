package com.vacancytracker.matching;

import com.vacancytracker.common.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profiles with full version history (T-049, ADR-010). Every save, including a restore, writes a new
 * version and a snapshot; earlier versions are never changed.
 */
@Service
public class SearchProfileService {

    public record VersionSummary(int version, Instant savedAt) {
    }

    private final SearchProfileRepository repository;
    private final ProfileValidator validator;
    private final Clock clock;

    public SearchProfileService(SearchProfileRepository repository, ProfileValidator validator, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public SearchProfile create(ProfileInput input) {
        ProfileInput content = validator.validate(input);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        repository.insert(id, content, 1, now);
        repository.insertVersion(id, 1, content, now);
        return get(id);
    }

    @Transactional
    public SearchProfile update(UUID id, ProfileInput input) {
        SearchProfile current = get(id);
        ProfileInput content = validator.validate(input);
        int next = current.version() + 1;
        Instant now = clock.instant();
        repository.update(id, content, next, now);
        repository.insertVersion(id, next, content, now);
        return get(id);
    }

    @Transactional(readOnly = true)
    public SearchProfile get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("Profile not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<SearchProfile> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<VersionSummary> versions(UUID id) {
        get(id);
        return repository.versions(id).stream().map(v -> new VersionSummary(v.version(), v.savedAt())).toList();
    }

    /** A past version, readable with the same shape as the current profile. */
    @Transactional(readOnly = true)
    public SearchProfile version(UUID id, int version) {
        SearchProfileRepository.Snapshot snapshot = repository.snapshot(id, version)
                .orElseThrow(() -> new NotFoundException("Version " + version + " of profile " + id + " not found"));
        ProfileInput c = snapshot.content();
        return new SearchProfile(id, c.name(), c.enabled(), c.sources(), c.scheduleOverride(), c.thresholds(),
                c.criteria(), version, snapshot.savedAt());
    }

    /** Restoring copies an old version's content into a new version; history stays linear (ADR-010). */
    @Transactional
    public SearchProfile restore(UUID id, int version) {
        return update(id, version(id, version).content());
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) {
            throw new NotFoundException("Profile not found: " + id);
        }
    }
}
