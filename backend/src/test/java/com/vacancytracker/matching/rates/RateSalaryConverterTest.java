package com.vacancytracker.matching.rates;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.Criterion;
import com.vacancytracker.matching.RuleEngine;
import com.vacancytracker.matching.RuleEngine.SalaryFact;
import com.vacancytracker.matching.RuleEngine.Verdict;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class RateSalaryConverterTest extends AbstractIntegrationTest {

    private static final String FLOOR_50K_EUR = "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}";

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private ExchangeRateService service;

    @Autowired
    private RateSalaryConverter converter;

    @Autowired
    private JdbcTemplate template;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void rates() {
        template.execute("TRUNCATE exchange_rate");
        repository.saveAll(LocalDate.of(2026, 10, 30),
                Map.of("USD", new BigDecimal("1.1172"), "PLN", new BigDecimal("4.4038")),
                "test", Instant.parse("2026-11-01T10:00:00Z"));
        service.reload();
    }

    private Verdict verdict(String op, String threshold, SalaryFact fact) throws JsonProcessingException {
        Criterion c = new Criterion("sal", "salary", op, mapper.readTree(threshold), "prefer", 5, null, null);
        return new RuleEngine(converter).evaluate(List.of(c), Map.of("salary", fact)).results().get(0).verdict();
    }

    @Test
    void turnsPeriodsAndCurrenciesIntoYearlyAmountsInTheTargetCurrency() {
        // 4500 EUR a month = 54000 EUR a year = 60328.80 USD a year
        assertThat(converter.toYearly(new BigDecimal("4500"), "EUR", "month", "EUR").orElseThrow()).isEqualByComparingTo("54000");
        assertThat(converter.toYearly(new BigDecimal("4500"), "EUR", "month", "USD").orElseThrow()).isEqualByComparingTo("60328.8");
        assertThat(converter.toYearly(new BigDecimal("60000"), "USD", "year", "EUR").orElseThrow().setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("53705.69");
    }

    @Test
    void anUnknownPeriodOrCurrencyIsMissingData() {
        assertThat(converter.toYearly(new BigDecimal("30"), "EUR", "hour", "EUR")).isEmpty();
        assertThat(converter.toYearly(new BigDecimal("30"), "EUR", null, "EUR")).isEmpty();
        assertThat(converter.toYearly(new BigDecimal("30000"), "ZZZ", "year", "EUR")).isEmpty();
        assertThat(converter.toYearly(null, "EUR", "year", "EUR")).isEmpty();
    }

    @Test
    void aDollarSalaryIsComparedWithAnEuroThreshold() throws Exception {
        // 60000 USD a year is about 53706 EUR: above a 50000 floor.
        assertThat(verdict("gte", FLOOR_50K_EUR, new SalaryFact(new BigDecimal("60000"), null, "USD", "year")))
                .isEqualTo(Verdict.SATISFIED);
        // 4500 USD a month is 54000 USD a year, about 48336 EUR: below the floor.
        assertThat(verdict("gte", FLOOR_50K_EUR, new SalaryFact(new BigDecimal("4500"), null, "USD", "month")))
                .isEqualTo(Verdict.NOT_SATISFIED);
    }

    @Test
    void aSalaryInACurrencyWithoutARateIsMissingNotFalse() throws Exception {
        assertThat(verdict("gte", FLOOR_50K_EUR, new SalaryFact(new BigDecimal("90000"), null, "ZZZ", "year")))
                .isEqualTo(Verdict.MISSING);
    }

    @Test
    void theThresholdCanBeInAnotherCurrencyToo() throws Exception {
        String floorPln = "{\"amount\":200000,\"currency\":\"PLN\",\"period\":\"year\"}";

        // 50000 EUR = 220190 PLN, so it clears a 200000 PLN floor.
        assertThat(verdict("gte", floorPln, new SalaryFact(new BigDecimal("50000"), null, "EUR", "year")))
                .isEqualTo(Verdict.SATISFIED);
    }
}
