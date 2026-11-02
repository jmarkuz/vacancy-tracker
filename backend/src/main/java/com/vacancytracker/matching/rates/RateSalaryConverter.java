package com.vacancytracker.matching.rates;

import com.vacancytracker.matching.RuleEngine;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Salary normalization for the rule engine (T-051): the amount is turned into a yearly amount first, then
 * converted into the target currency. An unknown period or a currency without a rate is missing data.
 */
@Component
public class RateSalaryConverter implements RuleEngine.SalaryConverter {

    private final ExchangeRateService rates;

    public RateSalaryConverter(ExchangeRateService rates) {
        this.rates = rates;
    }

    @Override
    public Optional<BigDecimal> toYearly(BigDecimal amount, String currency, String period, String targetCurrency) {
        if (amount == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> yearly = yearly(amount, period);
        return yearly.flatMap(y -> rates.convert(y, currency, targetCurrency));
    }

    private static Optional<BigDecimal> yearly(BigDecimal amount, String period) {
        if ("year".equals(period)) {
            return Optional.of(amount);
        }
        if ("month".equals(period)) {
            return Optional.of(amount.multiply(BigDecimal.valueOf(12)));
        }
        return Optional.empty();
    }
}
