package com.vacancytracker.matching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stored profile at one version. Earlier versions are read back with the same shape. */
public record SearchProfile(
        UUID id,
        String name,
        boolean enabled,
        List<String> sources,
        String scheduleOverride,
        Thresholds thresholds,
        List<Criterion> criteria,
        int version,
        Instant updatedAt) {

    public ProfileInput content() {
        return new ProfileInput(name, enabled, sources, scheduleOverride, thresholds, criteria);
    }
}
