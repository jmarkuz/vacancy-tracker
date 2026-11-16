package com.vacancytracker.collection.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.collection.adapter.RawPosting;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Field mapping of forwarded Telegram messages, without a network or a database. */
class TelegramMappingTest {

    private static final String UPDATES = """
            {"ok":true,"result":[
              {"update_id":1,"message":{"message_id":1,"date":1793600000,"chat":{"id":42},
                "text":"Senior Java Developer\\nКомпанія: Acme Payments\\nЛокація: Київ, Україна\\nЗарплата: $4000-5000 на місяць\\nDetails and Apply here",
                "entities":[{"type":"text_link","offset":90,"length":10,"url":"https://jobs.acme.example/apply/55?utm_source=tg"}]}},
              {"update_id":2,"message":{"message_id":2,"date":1793600100,"chat":{"id":42},
                "text":"QA Engineer\\nКомпанія: Beta\\nЗП: за домовленістю"}}
            ]}
            """;

    private final TelegramForwardAdapter adapter =
            new TelegramForwardAdapter(null, new ObjectMapper(), null, true, "t", "42", "http://unused");

    @Test
    void aLinkHiddenBehindTheTextOfAnEntityIsThePostingLink() {
        RawPosting posting = adapter.parse(UPDATES).postings().get(0);

        assertThat(posting.url()).isEqualTo("https://jobs.acme.example/apply/55?utm_source=tg");
    }

    @Test
    void thePlaceAndTheSalaryOfLabelledLinesAreRead() {
        RawPosting posting = adapter.parse(UPDATES).postings().get(0);

        assertThat(posting.location()).isEqualTo("Київ, Україна");
        assertThat(posting.salaryMin()).isEqualByComparingTo("4000");
        assertThat(posting.salaryMax()).isEqualByComparingTo("5000");
        assertThat(posting.currency()).isEqualTo("USD");
        assertThat(posting.salaryPeriod()).isEqualTo("month");
        assertThat(posting.postedAt()).isEqualTo(Instant.ofEpochSecond(1793600000));
    }

    @Test
    void aSalaryLineWithoutAmountsGivesNoSalary() {
        RawPosting posting = adapter.parse(UPDATES).postings().get(1);

        assertThat(posting.salaryMin()).isNull();
        assertThat(posting.currency()).isNull();
        assertThat(posting.location()).isNull();
    }
}
