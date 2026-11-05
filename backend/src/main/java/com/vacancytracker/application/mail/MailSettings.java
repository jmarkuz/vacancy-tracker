package com.vacancytracker.application.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How often the mailbox is read. The owner picks one of a few steps on the Mail tab; the choice is kept in the
 * database. Until one is chosen, the property app.mail.replies.interval is the default.
 */
@Component
public class MailSettings {

    /** The steps the owner can choose, in minutes. */
    public static final List<Integer> ALLOWED_MINUTES = List.of(5, 10, 15, 30, 45, 60);
    static final String KEY = "mail.interval.minutes";

    private final JdbcClient jdbc;
    private final int defaultMinutes;

    MailSettings(JdbcClient jdbc, @Value("${app.mail.replies.interval:PT15M}") Duration defaultInterval) {
        this.jdbc = jdbc;
        long minutes = defaultInterval.toMinutes();
        this.defaultMinutes = ALLOWED_MINUTES.contains((int) minutes) ? (int) minutes : 15;
    }

    /** The chosen interval in minutes, or the default when none is stored (or the stored value is not a known step). */
    public int intervalMinutes() {
        Optional<String> stored = jdbc.sql("SELECT value FROM app_setting WHERE key = :key").param("key", KEY).query(String.class).optional();
        try {
            int minutes = stored.map(Integer::parseInt).orElse(defaultMinutes);
            return ALLOWED_MINUTES.contains(minutes) ? minutes : defaultMinutes;
        } catch (NumberFormatException e) {
            return defaultMinutes;
        }
    }

    public Duration interval() {
        return Duration.ofMinutes(intervalMinutes());
    }

    /** Stores the interval; anything but one of {@link #ALLOWED_MINUTES} is refused. */
    public int setIntervalMinutes(int minutes) {
        if (!ALLOWED_MINUTES.contains(minutes)) {
            throw new IllegalArgumentException("The interval must be one of " + ALLOWED_MINUTES + " minutes.");
        }
        jdbc.sql("INSERT INTO app_setting (key, value, updated_at) VALUES (:key, :value, now())"
                        + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()")
                .param("key", KEY).param("value", Integer.toString(minutes)).update();
        return minutes;
    }

    /** Whether a read is due: the first one at once, then when a full interval has passed since the last. */
    static boolean due(Instant lastSync, Duration interval, Instant now) {
        return lastSync == null || !lastSync.plus(interval).isAfter(now);
    }
}
