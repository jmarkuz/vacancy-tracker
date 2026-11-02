package com.vacancytracker.matching.rates;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stored daily reference rates with EUR as base (ADR-011). */
@Repository
public class ExchangeRateRepository {

    static final String BASE = "EUR";

    private final JdbcClient jdbc;

    public ExchangeRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the rates of one day. A repeated day overwrites its rows, so a refresh is idempotent. */
    public void saveAll(LocalDate date, Map<String, BigDecimal> perEur, String source, Instant fetchedAt) {
        OffsetDateTime at = OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC);
        for (Map.Entry<String, BigDecimal> e : perEur.entrySet()) {
            jdbc.sql("INSERT INTO exchange_rate (base, quote, rate_date, rate, source, fetched_at)"
                            + " VALUES (:base, :quote, :date, :rate, :source, :at)"
                            + " ON CONFLICT (base, quote, rate_date) DO UPDATE SET rate = EXCLUDED.rate,"
                            + " source = EXCLUDED.source, fetched_at = EXCLUDED.fetched_at")
                    .param("base", BASE)
                    .param("quote", e.getKey())
                    .param("date", date)
                    .param("rate", e.getValue())
                    .param("source", source)
                    .param("at", at)
                    .update();
        }
    }

    /** The newest rate of each currency, so a currency missing from a later feed keeps its last known rate. */
    public Optional<LatestRates> latest() {
        Map<String, BigDecimal> perEur = new HashMap<>();
        LocalDate[] newest = new LocalDate[1];
        jdbc.sql("SELECT DISTINCT ON (quote) quote, rate, rate_date FROM exchange_rate WHERE base = :base"
                        + " ORDER BY quote, rate_date DESC")
                .param("base", BASE)
                .query((rs, n) -> {
                    perEur.put(rs.getString("quote"), rs.getBigDecimal("rate"));
                    LocalDate d = rs.getObject("rate_date", LocalDate.class);
                    if (newest[0] == null || d.isAfter(newest[0])) {
                        newest[0] = d;
                    }
                    return d;
                })
                .list();
        return perEur.isEmpty() ? Optional.empty() : Optional.of(new LatestRates(newest[0], Map.copyOf(perEur)));
    }
}
