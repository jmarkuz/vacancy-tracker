package com.vacancytracker.collection.adapter.jobicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.RawPosting;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** Field mapping of the Jobicy payload, without a network or a database. */
class JobicyMappingTest {

    private static final String JSON = """
            {"jobs": [
              {"id": 1, "url": "https://jobicy.com/jobs/1-a", "jobTitle": "Analyst", "companyName": "Marrow.io",
               "jobGeo": "Anywhere", "jobDescription": "<p>Analyse.</p>", "pubDate": "2026-10-30 10:00:00",
               "annualSalaryMin": 60000, "annualSalaryMax": "90000", "salaryCurrency": "EUR"},
              {"id": 2, "url": "https://jobicy.com/jobs/2-b", "jobTitle": "Writer", "companyName": "Beta",
               "jobGeo": "USA", "jobDescription": "<p>Write.</p>", "pubDate": "2026-10-30 10:00:00",
               "annualSalaryMin": "", "annualSalaryMax": null}
            ]}
            """;

    private final JobicyAdapter adapter = new JobicyAdapter(null, null, new ObjectMapper(), Clock.systemUTC(), "http://unused", "");

    @Test
    void theAnnualSalaryFieldsAreMapped() {
        RawPosting posting = adapter.parse(JSON).get(0);

        assertThat(posting.salaryMin()).isEqualByComparingTo("60000");
        assertThat(posting.salaryMax()).isEqualByComparingTo("90000");
        assertThat(posting.currency()).isEqualTo("EUR");
        assertThat(posting.salaryPeriod()).isEqualTo("year");
    }

    @Test
    void aPostingWithoutSalaryFieldsHasNoSalary() {
        RawPosting posting = adapter.parse(JSON).get(1);

        assertThat(posting.salaryMin()).isNull();
        assertThat(posting.currency()).isNull();
        assertThat(posting.salaryPeriod()).isNull();
        assertThat(posting.location()).isEqualTo("USA");
    }

    @Test
    void sourceDatesAreReadAsUtc() {
        assertThat(adapter.parse(JSON).get(0).postedAt()).isEqualTo(java.time.Instant.parse("2026-10-30T10:00:00Z"));
    }
}
