package com.vacancytracker.vacancy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reply statistics for the Dashboard: of the applications submitted in the last N days (the vacancy reached Applied or
 * a later status), how many got a linked mail, how long the first one took and what the mails were. The period is N
 * calendar days in the server's time zone, today included, starting at midnight. Only mails received after the
 * application date count, and only mails linked to the vacancy.
 */
@Service
public class RepliesService {

    static final int MAX_DAYS = 365;
    /** The statuses that mean the application was sent. */
    static final List<String> SENT = List.of("Applied", "Screening", "Interview", "Test task", "Offer", "Accepted");

    /** Linked mails by outcome; everything that is not one of the named categories is Other. */
    public record Outcomes(long interview, long testTask, long offer, long rejection, long other) {
    }

    /** medianDays is null when no application has a reply yet. */
    public record Replies(int days, long applied, long replied, Double medianDays, Outcomes outcomes) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public RepliesService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Replies replies(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
        Instant now = clock.instant();
        ZoneId zone = clock.getZone();
        Instant from = now.atZone(zone).toLocalDate().minusDays(days - 1L).atStartOfDay(zone).toInstant();
        String[] sent = SENT.toArray(String[]::new);

        // One row per application: the day it first reached a sent status and the first mail received after that day.
        String applications = """
                SELECT a.vacancy_id, a.applied_at, min(c.received_at) AS first_reply
                FROM (
                    SELECT vacancy_id, min(at) AS applied_at FROM (%s) effective
                    WHERE to_status = ANY (:sent) GROUP BY vacancy_id HAVING min(at) >= :from AND min(at) <= :now
                ) a
                LEFT JOIN correspondence c ON c.vacancy_id = a.vacancy_id AND c.received_at > a.applied_at
                GROUP BY a.vacancy_id, a.applied_at""".formatted(DashboardService.EFFECTIVE_EVENTS);

        var summary = jdbc.sql("""
                        SELECT count(*) AS applied, count(first_reply) AS replied,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM first_reply - applied_at) / 86400.0) AS median
                        FROM (%s) per_application""".formatted(applications))
                .param("sent", sent).param("from", Timestamp.from(from)).param("now", Timestamp.from(now))
                .query((rs, i) -> {
                    double median = rs.getDouble("median");
                    Double rounded = rs.wasNull() ? null : Math.round(median * 10) / 10.0;
                    return new Object[] {rs.getLong("applied"), rs.getLong("replied"), rounded};
                })
                .single();

        long[] counts = new long[5];
        jdbc.sql("""
                        SELECT c.category, count(*) AS n FROM correspondence c
                        JOIN (SELECT vacancy_id, min(at) AS applied_at FROM (%s) effective
                              WHERE to_status = ANY (:sent) GROUP BY vacancy_id HAVING min(at) >= :from AND min(at) <= :now) a
                          ON a.vacancy_id = c.vacancy_id AND c.received_at > a.applied_at
                        GROUP BY c.category""".formatted(DashboardService.EFFECTIVE_EVENTS))
                .param("sent", sent).param("from", Timestamp.from(from)).param("now", Timestamp.from(now))
                .query((rs, i) -> {
                    counts[bucket(rs.getString("category"))] += rs.getLong("n");
                    return null;
                })
                .list();
        return new Replies(days, (Long) summary[0], (Long) summary[1], (Double) summary[2],
                new Outcomes(counts[0], counts[1], counts[2], counts[3], counts[4]));
    }

    /** 0 interview, 1 test task, 2 offer, 3 rejection, 4 other. */
    static int bucket(String category) {
        if (category == null) {
            return 4;
        }
        return switch (category) {
            case "interview" -> 0;
            case "test_task" -> 1;
            case "offer" -> 2;
            case "rejection" -> 3;
            default -> 4;
        };
    }
}
