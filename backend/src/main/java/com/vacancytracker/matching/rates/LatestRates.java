package com.vacancytracker.matching.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** The newest known rate of each currency: units of the currency per one EUR, and the newest rate date. */
public record LatestRates(LocalDate date, Map<String, BigDecimal> perEur) {
}
