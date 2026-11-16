package com.vacancytracker.matching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A vacancy's score and reasons for one profile at one profile version. score is null when hidden. coverage (0-100) is
 * the share of the profile's scoring rules the posting could be judged by; null when hidden, or when the row was
 * scored before coverage existed.
 */
public record VacancyMatch(UUID vacancyId, UUID profileId, int profileVersion, Integer score, boolean hidden,
                           List<MatchReason> reasons, Instant matchedAt, Integer coverage) {
}
