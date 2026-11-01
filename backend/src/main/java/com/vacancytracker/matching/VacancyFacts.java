package com.vacancytracker.matching;

import com.vacancytracker.matching.RuleEngine.SalaryFact;
import com.vacancytracker.vacancy.Vacancy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the facts the rule engine reads from a vacancy. A field the vacancy does not store (company type,
 * industry, seniority, employment type, language, visa and relocation flags, posting age) is left out, so
 * the engine reports it as missing and the criterion's missing-value policy applies. The source fact is the
 * earliest adapter that saw the vacancy.
 */
final class VacancyFacts {

    private VacancyFacts() {
    }

    static Map<String, Object> of(Vacancy v, List<String> adapters) {
        Map<String, Object> facts = new HashMap<>();
        put(facts, "title", v.getTitle());
        put(facts, "description", v.getDescription());
        put(facts, "company", v.getCompany());
        put(facts, "location", v.getLocation());
        put(facts, "country", v.getCountry());
        put(facts, "workMode", v.getWorkMode());
        facts.put("tags", List.copyOf(v.getTags()));
        if (!adapters.isEmpty()) {
            facts.put("source", adapters.get(0));
        }
        if (v.getSalaryMin() != null || v.getSalaryMax() != null) {
            facts.put("salary", new SalaryFact(v.getSalaryMin(), v.getSalaryMax(), v.getCurrency(), v.getSalaryPeriod()));
        }
        return facts;
    }

    private static void put(Map<String, Object> facts, String key, String value) {
        if (value != null && !value.isBlank()) {
            facts.put(key, value);
        }
    }
}
