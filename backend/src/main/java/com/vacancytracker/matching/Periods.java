package com.vacancytracker.matching;

import java.math.BigDecimal;
import java.util.Optional;

/** Period to yearly conversion. Hourly and other periods cannot be compared and are reported as unknown. */
final class Periods {

    private Periods() {
    }

    static Optional<BigDecimal> yearly(BigDecimal amount, String period) {
        if ("year".equals(period)) {
            return Optional.of(amount);
        }
        if ("month".equals(period)) {
            return Optional.of(amount.multiply(BigDecimal.valueOf(12)));
        }
        return Optional.empty();
    }
}
