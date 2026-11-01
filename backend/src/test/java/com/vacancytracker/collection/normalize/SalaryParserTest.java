package com.vacancytracker.collection.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SalaryParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "€70–85k / yr gross|70000|85000|EUR|year",
            "$80,000 - 95,000 a year|80000|95000|USD|year",
            "PLN 18–22k / mo|18000|22000|PLN|month",
            "50k-60k EUR per month|50000|60000|EUR|month",
            "£45k|45000|45000|GBP|null",
            "CHF 4.500 per hour|4500|4500|CHF|hour",
            "95.000 EUR annual|95000|95000|EUR|year",
            "18.5 USD per hour|18.5|18.5|USD|hour",
    })
    void readsRangesCurrenciesAndPeriods(String text, String min, String max, String currency, String period) {
        var salary = SalaryParser.parse(text).orElseThrow();

        assertThat(salary.min()).isEqualByComparingTo(new BigDecimal(min));
        assertThat(salary.max()).isEqualByComparingTo(new BigDecimal(max));
        assertThat(salary.currency()).isEqualTo(currency);
        assertThat(salary.period()).isEqualTo("null".equals(period) ? null : period);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not stated", "competitive", "   "})
    void textWithoutAmountGivesNothing(String text) {
        assertThat(SalaryParser.parse(text)).isEmpty();
    }

    @Test
    void reversedRangeIsReadInOrder() {
        var salary = SalaryParser.parse("EUR 90k - 70k yearly").orElseThrow();
        assertThat(salary.min()).isEqualByComparingTo("70000");
        assertThat(salary.max()).isEqualByComparingTo("90000");
    }
}
