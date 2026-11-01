package com.vacancytracker.collection.rates;

import com.vacancytracker.collection.http.FetchResult;
import com.vacancytracker.collection.http.HttpFetcher;
import com.vacancytracker.collection.http.UpstreamException;
import com.vacancytracker.matching.rates.ExchangeRateRepository;
import com.vacancytracker.matching.rates.ExchangeRateService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fetches the ECB daily reference rates through the shared HTTP client (T-051). The feed has no stated
 * request limit; it is asked once a day after the ECB publishes (about 16:00 CET) and answers Not Modified
 * on days without a new fixing. When the feed cannot be read, the last known rates stay in use.
 */
@Service
public class EcbRateRefresher {

    static final String SOURCE = "European Central Bank";
    private static final Logger LOG = LoggerFactory.getLogger(EcbRateRefresher.class);

    private final HttpFetcher fetcher;
    private final ExchangeRateRepository repository;
    private final ExchangeRateService service;
    private final Clock clock;
    private final String url;

    public EcbRateRefresher(HttpFetcher fetcher, ExchangeRateRepository repository, ExchangeRateService service,
                            Clock clock,
                            @Value("${app.rates.url:https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml}") String url) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.service = service;
        this.clock = clock;
        this.url = url;
    }

    /** Returns true when new rates were stored, false when the feed was unchanged. Throws if it cannot be read. */
    public boolean refresh() {
        FetchResult result = fetcher.get(url);
        if (result.notModified()) {
            return false;
        }
        if (!result.ok()) {
            throw new UpstreamException("ECB rates responded HTTP " + result.status());
        }
        EcbFeedParser.Parsed parsed;
        try {
            parsed = EcbFeedParser.parse(result.body());
        } catch (IllegalArgumentException e) {
            throw new UpstreamException(e.getMessage());
        }
        repository.saveAll(parsed.date(), parsed.perEur(), SOURCE, clock.instant());
        service.reload();
        return true;
    }

    /** Refresh that never throws: a failure is logged and the last known rates stay in use. */
    public void refreshQuietly() {
        try {
            boolean updated = refresh();
            LOG.info("Exchange rates {}", updated ? "updated" : "unchanged");
        } catch (UpstreamException e) {
            LOG.warn("Exchange rates not updated, keeping the last known rates: {}", e.getMessage());
        }
    }
}
