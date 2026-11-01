package com.vacancytracker.collection.rates;

import com.vacancytracker.matching.rates.ExchangeRateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Loads stored rates at startup, optionally refreshes once, and then refreshes each working day. */
@Component
public class RateScheduler {

    private final EcbRateRefresher refresher;
    private final ExchangeRateService service;
    private final boolean refreshOnStartup;

    public RateScheduler(EcbRateRefresher refresher, ExchangeRateService service,
                         @Value("${app.rates.refresh-on-startup:true}") boolean refreshOnStartup) {
        this.refresher = refresher;
        this.service = service;
        this.refreshOnStartup = refreshOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        service.reload();
        if (refreshOnStartup) {
            refresher.refreshQuietly();
        }
    }

    /** Half an hour after the ECB's usual 16:00 CET publication, on working days. */
    @Scheduled(cron = "${app.rates.cron:0 30 16 * * MON-FRI}", zone = "${app.rates.zone:Europe/Berlin}")
    public void daily() {
        refresher.refreshQuietly();
    }
}
