package com.vacancytracker.matching;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Converts by period only and accepts a single currency, the profile's. Any other currency is treated as
 * missing data (ADR-011). Exchange rates replace this in T-051.
 */
public final class YearlyConverter implements RuleEngine.SalaryConverter {

    private final String currency;

    public YearlyConverter(String profileCurrency) {
        this.currency = profileCurrency.toUpperCase(Locale.ROOT);
    }

    @Override
    public Optional<BigDecimal> toYearly(BigDecimal amount, String amountCurrency, String period) {
        if (amount == null || amountCurrency == null || !currency.equals(amountCurrency.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        if ("year".equals(period)) {
            return Optional.of(amount);
        }
        if ("month".equals(period)) {
            return Optional.of(amount.multiply(BigDecimal.valueOf(12)));
        }
        return Optional.empty();
    }
}
