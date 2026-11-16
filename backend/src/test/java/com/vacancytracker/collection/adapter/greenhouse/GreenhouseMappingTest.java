package com.vacancytracker.collection.adapter.greenhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.RawPosting;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Field mapping of the Greenhouse job board payload, without a network or a database. */
class GreenhouseMappingTest {

    private static final String JSON = """
            {"jobs": [
              {"id": 7001, "title": "Staff Engineer",
               "absolute_url": "https://boards.greenhouse.io/acme/jobs/7001?gh_src=abc",
               "location": {"name": "Remote - EU"}, "content": "&lt;p&gt;Lead the &lt;b&gt;team&lt;/b&gt;.&lt;/p&gt;",
               "first_published": "2026-10-20T08:00:00-04:00", "updated_at": "2026-10-30T09:00:00-04:00",
               "pay_input_ranges": [
                 {"min_cents": 15000000, "max_cents": 20000000, "currency_type": "USD", "title": "US", "blurb": "..."},
                 {"min_cents": 12000000, "max_cents": 17000000, "currency_type": "USD", "title": "Other", "blurb": "..."}]},
              {"id": 7002, "title": "Analyst", "absolute_url": "https://boards.greenhouse.io/acme/jobs/7002",
               "location": {"name": "Berlin"}, "content": "", "updated_at": "2026-10-30T09:00:00Z"}
            ]}
            """;

    private final GreenhouseAdapter adapter = new GreenhouseAdapter(null, new ObjectMapper(), List.of(), "http://unused");

    @Test
    void thePayRangeIsReadInUnitsNotCents() {
        RawPosting posting = adapter.parse("acme", JSON).get(0);

        assertThat(posting.salaryMin()).isEqualByComparingTo("120000");
        assertThat(posting.salaryMax()).isEqualByComparingTo("200000");
        assertThat(posting.currency()).isEqualTo("USD");
        assertThat(posting.salaryPeriod()).isNull();
        assertThat(adapter.parse("acme", JSON).get(1).salaryMin()).isNull();
    }
}
