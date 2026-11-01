package com.vacancytracker.matching;

import com.vacancytracker.common.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Company allowlist and blocklist (T-059, ADR-028). Changing a list does not rescore by itself; the caller does. */
@Service
public class CompanyListService {

    static final int MAX_NAME = 200;

    private final CompanyListRepository repository;
    private final SearchProfileService profiles;

    public CompanyListService(CompanyListRepository repository, SearchProfileService profiles) {
        this.repository = repository;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public List<CompanyListEntry> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public CompanyLists snapshot() {
        return new CompanyLists(repository.findAll());
    }

    /** Adding a company that is already on the list changes nothing and returns the existing entry. */
    @Transactional
    public CompanyListEntry add(String company, String listType, UUID profileId) {
        String name = company == null ? "" : company.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new ProfileValidationException(Map.of("company", "Enter a company name"));
        }
        if (name.length() > MAX_NAME) {
            throw new ProfileValidationException(Map.of("company", "At most " + MAX_NAME + " characters"));
        }
        if (!"allowlist".equals(listType) && !"blocklist".equals(listType)) {
            throw new ProfileValidationException(Map.of("listType", "Must be allowlist or blocklist"));
        }
        if (profileId != null) {
            profiles.get(profileId);
        }
        CompanyListEntry entry = new CompanyListEntry(UUID.randomUUID(), name, listType, profileId);
        repository.insertIfAbsent(entry);
        String key = CompanyLists.normalize(name);
        return repository.findAll().stream()
                .filter(e -> e.listType().equals(listType) && java.util.Objects.equals(e.profileId(), profileId)
                        && CompanyLists.normalize(e.company()).equals(key))
                .findFirst().orElseThrow();
    }

    @Transactional
    public void remove(UUID id) {
        if (!repository.delete(id)) {
            throw new NotFoundException("Company list entry not found: " + id);
        }
    }

    /** The profile's own entries as the two lists of a profile file. */
    @Transactional(readOnly = true)
    public Map<String, List<String>> forProfile(UUID profileId) {
        List<CompanyListEntry> own = repository.findAll().stream().filter(e -> profileId.equals(e.profileId())).toList();
        return Map.of(
                "allowlist", own.stream().filter(e -> e.listType().equals("allowlist")).map(CompanyListEntry::company).toList(),
                "blocklist", own.stream().filter(e -> e.listType().equals("blocklist")).map(CompanyListEntry::company).toList());
    }

    /** Replaces the profile's own entries. Global entries are not touched. */
    @Transactional
    public void replaceForProfile(UUID profileId, List<String> allowlist, List<String> blocklist) {
        repository.deleteForProfile(profileId);
        allowlist.forEach(c -> add(c, "allowlist", profileId));
        blocklist.forEach(c -> add(c, "blocklist", profileId));
    }
}
