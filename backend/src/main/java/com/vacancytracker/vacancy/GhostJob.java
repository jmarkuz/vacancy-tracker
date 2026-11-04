package com.vacancytracker.vacancy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Signs that a posting may be a "ghost": listed for a long time without a real vacancy behind it. The three signs that
 * guides agree on are used, each worked out from the owner's own data with no outside service: the posting is old, it
 * has stayed listed for a long time since it was first seen, and it names no salary. Two of the three make a vacancy
 * "likely"; the reasons are always given, so the owner can disagree with the flag (ADR-008: every score is explained).
 * It is advice, not a verdict: a vacancy without a posting date is judged only by the other two signs.
 */
public final class GhostJob {

    /** A posting this many days old, or listed this many days since first seen, counts as a sign. */
    public static final int LONG_DAYS = 30;
    /** How many signs make a vacancy "likely". */
    public static final int LIKELY_AT = 2;

    public record Assessment(int signs, boolean likely, List<String> reasons) {
    }

    private GhostJob() {
    }

    public static Assessment assess(Vacancy v, Instant now) {
        boolean hasSalary = v.getSalaryMin() != null || v.getSalaryMax() != null;
        return assess(v.getPostedAt(), v.getFirstSeenAt(), v.getLastSeenAt(), hasSalary, now);
    }

    static Assessment assess(Instant postedAt, Instant firstSeenAt, Instant lastSeenAt, boolean hasSalary, Instant now) {
        List<String> reasons = new ArrayList<>();
        if (postedAt != null) {
            long age = ChronoUnit.DAYS.between(postedAt, now);
            if (age >= LONG_DAYS) {
                reasons.add("Posted " + age + " days ago");
            }
        }
        if (firstSeenAt != null && lastSeenAt != null) {
            long listed = ChronoUnit.DAYS.between(firstSeenAt, lastSeenAt);
            if (listed >= LONG_DAYS) {
                reasons.add("Still listed " + listed + " days after we first saw it");
            }
        }
        if (!hasSalary) {
            reasons.add("No salary stated");
        }
        return new Assessment(reasons.size(), reasons.size() >= LIKELY_AT, List.copyOf(reasons));
    }
}
