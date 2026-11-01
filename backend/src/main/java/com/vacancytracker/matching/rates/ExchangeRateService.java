package com.vacancytracker.matching.rates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Converts between currencies with the latest stored daily rates (ADR-011). Rates are held in memory and
 * reloaded after each refresh. A currency without a known rate gives an empty result: the salary is then
 * treated as missing data and never guessed. The rates come from the European Central Bank; show that
 * source wherever a converted amount is displayed.
 */
@Service
public class ExchangeRateService {

    private final ExchangeRateRepository repository;
    private volatile LatestRates latest;

    public ExchangeRateService(ExchangeRateRepository repository) {
        this.repository = repository;
    }

    /** Reads the newest rates from the database into memory. Called at startup and after each refresh. */
    public void reload() {
        latest = repository.latest().orElse(null);
    }

    /** Date of the newest rate in use, or empty when no rates are stored yet. */
    public Optional<LocalDate> rateDate() {
        LatestRates current = latest;
        return current == null ? Optional.empty() : Optional.of(current.date());
    }

    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
        if (amount == null || from == null || to == null) {
            return Optional.empty();
        }
        String source = from.toUpperCase(Locale.ROOT);
        String target = to.toUpperCase(Locale.ROOT);
        if (source.equals(target)) {
            return Optional.of(amount);
        }
        LatestRates current = latest;
        if (current == null) {
            return Optional.empty();
        }
        BigDecimal sourceRate = perEur(current, source);
        BigDecimal targetRate = perEur(current, target);
        if (sourceRate == null || targetRate == null) {
            return Optional.empty();
        }
        return Optional.of(amount.multiply(targetRate).divide(sourceRate, 8, RoundingMode.HALF_UP));
    }

    private static BigDecimal perEur(LatestRates rates, String currency) {
        return "EUR".equals(currency) ? BigDecimal.ONE : rates.perEur().get(currency);
    }
}
