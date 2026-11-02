package com.vacancytracker.matching;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Converts by period only and accepts no currency change: the amount's currency must be the target currency.
 * It is the converter for tests and for installations without exchange rates; {@link RateSalaryConverter}
 * adds currency conversion.
 */
public final class YearlyConverter implements RuleEngine.SalaryConverter {

    @Override
    public Optional<BigDecimal> toYearly(BigDecimal amount, String currency, String period, String targetCurrency) {
        if (amount == null || currency == null || targetCurrency == null
                || !targetCurrency.toUpperCase(Locale.ROOT).equals(currency.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Periods.yearly(amount, period);
    }
}
