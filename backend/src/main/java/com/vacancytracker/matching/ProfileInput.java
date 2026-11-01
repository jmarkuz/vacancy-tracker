package com.vacancytracker.matching;

import java.util.List;

/**
 * The editable content of a search profile. It is what a version snapshot stores, and what an import or an
 * API request supplies. Id, version and timestamps belong to {@link SearchProfile}.
 */
public record ProfileInput(
        String name,
        boolean enabled,
        List<String> sources,
        String scheduleOverride,
        Thresholds thresholds,
        List<Criterion> criteria) {
}
