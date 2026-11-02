package com.vacancytracker.matching;

/**
 * One line of the explanation of a score, for example "+8: Keyword in description". criterionId refers to
 * the profile's criterion; delta is the points added or subtracted (0 for a filter or an allow/block list).
 * kind is prefer, avoid, require, exclude, missing, allowlist or blocklist.
 */
public record MatchReason(String criterionId, int delta, String kind, String text) {
}
