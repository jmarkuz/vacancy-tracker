package com.vacancytracker.collection.adapter.lever;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.RawPosting;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Field mapping of the Lever postings payload, without a network or a database. */
class LeverMappingTest {

    private static final String JSON = """
            [{
              "id": "lv-9", "text": "Backend Engineer", "hostedUrl": "https://jobs.lever.co/acme/lv-9",
              "categories": {"location": "Berlin", "team": "Platform", "allLocations": ["Berlin", "Munich"]},
              "country": "DE", "workplaceType": "unspecified", "createdAt": 1761900000000,
              "descriptionPlain": "We build the platform.",
              "lists": [
                {"text": "What you will do", "content": "<li>Design APIs</li><li>Run services</li>"},
                {"text": "What you bring", "content": "<li>5 years of Java</li>"}
              ],
              "additionalPlain": "We offer remote days.",
              "salaryRange": {"min": 70000, "max": 90000, "currency": "EUR", "interval": "per-year-salary"}
            },
            {
              "id": "lv-10", "text": "Designer", "hostedUrl": "https://jobs.lever.co/acme/lv-10",
              "categories": {"allLocations": ["Lisbon", "Porto"]},
              "descriptionPlain": "Design things."
            }]
            """;

    private final LeverAdapter adapter = new LeverAdapter(null, new ObjectMapper(), List.of(), "http://unused");

    @Test
    void theDescriptionHoldsTheOpeningTheListsAndTheClosing() {
        RawPosting posting = adapter.parse("acme", JSON).get(0);

        assertThat(posting.description()).contains("We build the platform.", "What you will do", "Design APIs",
                "Run services", "What you bring", "5 years of Java", "We offer remote days.");
    }

    @Test
    void theSalaryRangeAndTheCountryAreMapped() {
        RawPosting posting = adapter.parse("acme", JSON).get(0);

        assertThat(posting.salaryMin()).isEqualByComparingTo("70000");
        assertThat(posting.salaryMax()).isEqualByComparingTo("90000");
        assertThat(posting.currency()).isEqualTo("EUR");
        assertThat(posting.salaryPeriod()).isEqualTo("per-year-salary");
        assertThat(posting.country()).isEqualTo("DE");
        assertThat(posting.workMode()).isNull();
    }

    @Test
    void withoutACategoryLocationTheListedLocationsAreUsed() {
        RawPosting posting = adapter.parse("acme", JSON).get(1);

        assertThat(posting.location()).isEqualTo("Lisbon, Porto");
        assertThat(posting.salaryMin()).isNull();
        assertThat(posting.country()).isNull();
    }
}
