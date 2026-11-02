package com.vacancytracker.matching;

import java.util.List;
import java.util.UUID;

/** A named filter over vacancies (table saved_view). Criteria use the Criterion model (ADR-009). */
public record SavedView(UUID id, String name, List<Criterion> criteria, String sort, List<String> visibleColumns) {
}
