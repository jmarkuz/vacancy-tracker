package com.vacancytracker.vacancy;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline statuses (docs/search-profiles.md section 6 of the brief, see ADR-023 for transitions).
 */
public enum VacancyStatus {
    NEW("New"),
    SAVED("Saved"),
    APPLIED("Applied"),
    SCREENING("Screening"),
    INTERVIEW("Interview"),
    TEST_TASK("Test task"),
    OFFER("Offer"),
    ACCEPTED("Accepted"),
    REJECTED("Rejected"),
    WITHDRAWN("Withdrawn"),
    EXPIRED("Expired"),
    DISMISSED("Dismissed"),
    HIDDEN("Hidden");

    private static final Map<VacancyStatus, Set<VacancyStatus>> TRANSITIONS = new EnumMap<>(VacancyStatus.class);

    static {
        TRANSITIONS.put(NEW, EnumSet.of(SAVED, APPLIED, DISMISSED, HIDDEN, EXPIRED));
        TRANSITIONS.put(SAVED, EnumSet.of(APPLIED, DISMISSED, WITHDRAWN, EXPIRED));
        TRANSITIONS.put(APPLIED, EnumSet.of(SCREENING, REJECTED, WITHDRAWN));
        TRANSITIONS.put(SCREENING, EnumSet.of(INTERVIEW, TEST_TASK, REJECTED, WITHDRAWN));
        TRANSITIONS.put(INTERVIEW, EnumSet.of(TEST_TASK, OFFER, REJECTED, WITHDRAWN));
        TRANSITIONS.put(TEST_TASK, EnumSet.of(INTERVIEW, OFFER, REJECTED, WITHDRAWN));
        TRANSITIONS.put(OFFER, EnumSet.of(ACCEPTED, REJECTED, WITHDRAWN));
        TRANSITIONS.put(ACCEPTED, EnumSet.noneOf(VacancyStatus.class));
        // Terminal states can only be restored to New by the user (ADR-023).
        for (VacancyStatus s : EnumSet.of(REJECTED, WITHDRAWN, EXPIRED, DISMISSED, HIDDEN)) {
            TRANSITIONS.put(s, EnumSet.of(NEW));
        }
    }

    private final String label;

    VacancyStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Terminal statuses from the brief: they leave the active pipeline. Accepted has no way out either. */
    public boolean isTerminal() {
        return EnumSet.of(REJECTED, WITHDRAWN, EXPIRED, DISMISSED, HIDDEN).contains(this);
    }

    public Set<VacancyStatus> allowedNext() {
        return EnumSet.copyOf(TRANSITIONS.get(this));
    }

    public boolean canMoveTo(VacancyStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public static VacancyStatus fromLabel(String label) {
        return Arrays.stream(values())
                .filter(s -> s.label.equalsIgnoreCase(label) || s.name().equalsIgnoreCase(label))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown status: " + label));
    }
}
