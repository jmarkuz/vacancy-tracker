package com.vacancytracker.matching.rates;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ExchangeRateServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRateRepository repository;

    @Autowired
    private ExchangeRateService service;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE exchange_rate");
        service.reload();
    }

    private void store(LocalDate date, Map<String, String> rates) {
        Map<String, BigDecimal> parsed = new java.util.HashMap<>();
        rates.forEach((k, v) -> parsed.put(k, new BigDecimal(v)));
        repository.saveAll(date, parsed, "test", Instant.parse("2026-11-01T10:00:00Z"));
        service.reload();
    }

    @Test
    void convertsBetweenEurAndOtherCurrenciesInBothDirections() {
        store(LocalDate.of(2026, 10, 30), Map.of("USD", "1.1172", "PLN", "4.4038"));

        assertThat(service.convert(new BigDecimal("100"), "EUR", "USD").orElseThrow()).isEqualByComparingTo("111.72");
        assertThat(service.convert(new BigDecimal("111.72"), "USD", "EUR").orElseThrow()).isEqualByComparingTo("100");
    }

    @Test
    void crossRatesGoThroughEur() {
        store(LocalDate.of(2026, 10, 30), Map.of("USD", "1.1172", "PLN", "4.4038"));

        // 1117.2 USD = 1000 EUR = 4403.8 PLN
        assertThat(service.convert(new BigDecimal("1117.2"), "USD", "PLN").orElseThrow()).isEqualByComparingTo("4403.8");
    }

    @Test
    void currencyCodesAreCaseInsensitiveAndTheSameCurrencyNeedsNoRate() {
        assertThat(service.convert(new BigDecimal("50"), "eur", "EUR")).contains(new BigDecimal("50"));
        assertThat(service.convert(new BigDecimal("50"), "XYZ", "xyz")).contains(new BigDecimal("50"));

        store(LocalDate.of(2026, 10, 30), Map.of("USD", "1.1172"));
        assertThat(service.convert(new BigDecimal("111.72"), "usd", "eur").orElseThrow()).isEqualByComparingTo("100");
    }

    @Test
    void aMissingRateIsEmptyNeverGuessed() {
        store(LocalDate.of(2026, 10, 30), Map.of("USD", "1.1172"));

        assertThat(service.convert(new BigDecimal("100"), "EUR", "ZZZ")).isEmpty();
        assertThat(service.convert(new BigDecimal("100"), "ZZZ", "USD")).isEmpty();
        assertThat(service.convert(null, "EUR", "USD")).isEmpty();
    }

    @Test
    void withoutAnyStoredRatesOnlyTheSameCurrencyConverts() {
        assertThat(service.rateDate()).isEmpty();
        assertThat(service.convert(new BigDecimal("100"), "EUR", "USD")).isEmpty();
        assertThat(service.convert(new BigDecimal("100"), "EUR", "EUR")).isPresent();
    }

    @Test
    void usesTheNewestRateOfEachCurrencyAndKeepsOlderCurrencies() {
        store(LocalDate.of(2026, 10, 29), Map.of("USD", "1.1000", "PLN", "4.4000"));
        store(LocalDate.of(2026, 10, 30), Map.of("USD", "1.2000"));

        assertThat(service.rateDate()).contains(LocalDate.of(2026, 10, 30));
        assertThat(service.convert(new BigDecimal("120"), "USD", "EUR").orElseThrow()).isEqualByComparingTo("100");
        assertThat(service.convert(new BigDecimal("100"), "EUR", "PLN").orElseThrow()).isEqualByComparingTo("440");
    }

    @Test
    void savingTheSameDayTwiceOverwritesInsteadOfDuplicating() {
        store(LocalDate.of(2026, 10, 30), Map.of("USD", "1.1000"));
        store(LocalDate.of(2026, 10, 30), Map.of("USD", "1.2000"));

        assertThat(template.queryForObject("SELECT count(*) FROM exchange_rate", Integer.class)).isEqualTo(1);
        assertThat(service.convert(new BigDecimal("120"), "USD", "EUR").orElseThrow()).isEqualByComparingTo("100");
    }
}
