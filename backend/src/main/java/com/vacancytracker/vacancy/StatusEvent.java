package com.vacancytracker.vacancy;

import java.time.Instant;
import java.util.UUID;

/** One entry of a vacancy's status history (table status_event). */
public record StatusEvent(UUID vacancyId, VacancyStatus from, VacancyStatus to, Instant at, String note) {
}
