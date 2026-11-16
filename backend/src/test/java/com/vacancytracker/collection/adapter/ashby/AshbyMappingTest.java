package com.vacancytracker.collection.adapter.ashby;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.RawPosting;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Field mapping of the Ashby job board payload, without a network or a database. */
class AshbyMappingTest {

    private static final String JSON = """
            {"jobs": [
              {"id": "ab-5", "title": "Backend Engineer", "location": "Berlin", "isListed": true, "isRemote": false,
               "workplaceType": "Hybrid", "jobUrl": "https://jobs.ashbyhq.com/acme/ab-5",
               "publishedAt": "2026-10-30T09:00:00.000+00:00", "descriptionPlain": "Build services.",
               "address": {"postalAddress": {"addressLocality": "Berlin", "addressCountry": "Germany"}},
               "compensation": {"compensationTierSummary": "EUR 70K - 90K",
                 "summaryComponents": [
                   {"compensationType": "Salary", "interval": "1 YEAR", "currencyCode": "EUR", "minValue": 70000, "maxValue": 90000},
                   {"compensationType": "Bonus", "interval": "1 YEAR", "currencyCode": "EUR", "minValue": 5000, "maxValue": 8000}]}},
              {"id": "ab-6", "title": "Support Agent", "location": "Lisbon", "isListed": true, "isRemote": true,
               "jobUrl": "https://jobs.ashbyhq.com/acme/ab-6", "descriptionPlain": "Help customers.",
               "compensation": {"compensationTierSummary": "Competitive", "summaryComponents": []}}
            ]}
            """;

    private final AshbyAdapter adapter = new AshbyAdapter(null, new ObjectMapper(), List.of(), "http://unused");

    @Test
    void theSalaryComponentAndTheCountryAreMapped() {
        RawPosting posting = adapter.parse("acme", JSON).get(0);

        assertThat(posting.salaryMin()).isEqualByComparingTo("70000");
        assertThat(posting.salaryMax()).isEqualByComparingTo("90000");
        assertThat(posting.currency()).isEqualTo("EUR");
        assertThat(posting.salaryPeriod()).isEqualTo("1 YEAR");
        assertThat(posting.country()).isEqualTo("Germany");
        assertThat(posting.workMode()).isEqualTo("hybrid");
    }

    @Test
    void isRemoteStandsInWhenNoWorkplaceTypeIsGiven() {
        RawPosting posting = adapter.parse("acme", JSON).get(1);

        assertThat(posting.workMode()).isEqualTo("remote");
        assertThat(posting.salaryMin()).isNull();
        assertThat(posting.currency()).isNull();
    }
}
