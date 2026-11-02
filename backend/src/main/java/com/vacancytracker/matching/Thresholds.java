package com.vacancytracker.matching;

/** Per-profile thresholds (docs/search-profiles.md section 4). */
public record Thresholds(int inboxMinScore, int notifyMinScore, int maxAgeDays) {

    public static Thresholds defaults() {
        return new Thresholds(40, 70, 60);
    }
}
