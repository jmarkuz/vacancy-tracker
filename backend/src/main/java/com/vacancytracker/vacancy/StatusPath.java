package com.vacancytracker.vacancy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shortest way from one status to another through the transition table (ADR-023), for changes that follow from
 * an event rather than from a click: "I submitted" (Saved to Applied) or an interview invitation while the vacancy is
 * still Applied (Applied, Screening, Interview). Each step is a normal transition, so the history stays linear.
 */
public final class StatusPath {

    private StatusPath() {
    }

    /** The statuses to move through, without the start; empty when already there, absent when it cannot be reached. */
    public static Optional<List<VacancyStatus>> between(VacancyStatus from, VacancyStatus to) {
        if (from == to) {
            return Optional.of(List.of());
        }
        Map<VacancyStatus, VacancyStatus> previous = new EnumMap<>(VacancyStatus.class);
        Deque<VacancyStatus> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            VacancyStatus current = queue.removeFirst();
            for (VacancyStatus next : current.allowedNext()) {
                if (next == from || previous.containsKey(next)) {
                    continue;
                }
                previous.put(next, current);
                if (next == to) {
                    List<VacancyStatus> path = new ArrayList<>();
                    for (VacancyStatus s = to; s != from; s = previous.get(s)) {
                        path.add(s);
                    }
                    Collections.reverse(path);
                    return Optional.of(List.copyOf(path));
                }
                queue.addLast(next);
            }
        }
        return Optional.empty();
    }
}
