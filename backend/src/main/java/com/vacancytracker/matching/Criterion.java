package com.vacancytracker.matching;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Universal rule (docs/search-profiles.md section 2). Views use only require and exclude;
 * weight and missingValuePolicy are accepted for profiles and ignored by view filters.
 */
public record Criterion(
        String id,
        String field,
        String operator,
        JsonNode value,
        String mode,
        Integer weight,
        String missingValuePolicy,
        String label) {
}
