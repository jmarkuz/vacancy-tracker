package com.vacancytracker.vacancy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Numbers for the Dashboard (T-060). Active means a vacancy the user is working on: Saved up to Offer.
 * Weeks start on Monday in the server's time zone.
 */
@Service
public class DashboardService {

    static final List<String> ACTIVE = List.of("Saved", "Applied", "Screening", "Interview", "Test task", "Offer");
    /** The funnel stages in order; a vacancy that skipped a stage still counts in the earlier ones. */
    static final List<String> FUNNEL = List.of("Saved", "Applied", "Screening", "Interview", "Offer");
    static final int WEEKS = 8;
    static final int TOP = 5;
    static final int NEXT_ACTIONS = 5;

    public record TopMatch(UUID id, String title, String company, int score) {
    }

    public record NextAction(UUID id, String title, String company, String status, Instant dueAt, String note, boolean overdue) {
    }

    public record FunnelStage(String status, long count) {
    }

    public record Week(LocalDate start, long applications) {
    }

    public record Dashboard(long newToday, long inbox, long active, long interviews, long overdue,
                            List<TopMatch> topMatches, List<FunnelStage> funnel, List<Week> applicationsPerWeek,
                            List<NextAction> nextActions) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public DashboardService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Instant now = clock.instant();
        ZoneId zone = clock.getZone();
        Instant startOfToday = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        String[] active = ACTIVE.toArray(String[]::new);

        long newToday = count("SELECT count(*) FROM vacancy WHERE status = 'New' AND first_seen_at >= :since",
                "since", java.sql.Timestamp.from(startOfToday));
        long inbox = count("SELECT count(*) FROM vacancy WHERE status = 'New'", null, null);
        long activeCount = count("SELECT count(*) FROM vacancy WHERE status = ANY (:active)", "active", active);
        long interviews = count("SELECT count(*) FROM vacancy WHERE status = 'Interview'", null, null);
        long overdue = jdbc.sql("SELECT count(*) FROM vacancy WHERE status = ANY (:active) AND next_action_at < :now")
                .param("active", active).param("now", java.sql.Timestamp.from(now)).query(Long.class).single();

        List<TopMatch> top = jdbc.sql("SELECT id, title, company, match_score FROM vacancy WHERE status = 'New' AND match_score IS NOT NULL"
                        + " ORDER BY match_score DESC, last_seen_at DESC, id LIMIT :n")
                .param("n", TOP)
                .query((rs, i) -> new TopMatch(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("company"),
                        rs.getBigDecimal("match_score").intValue()))
                .list();

        List<NextAction> next = jdbc.sql("SELECT id, title, company, status, next_action_at, next_action_note FROM vacancy"
                        + " WHERE status = ANY (:active) AND next_action_at IS NOT NULL ORDER BY next_action_at, id LIMIT :n")
                .param("active", active).param("n", NEXT_ACTIONS)
                .query((rs, i) -> {
                    Instant due = rs.getTimestamp("next_action_at").toInstant();
                    return new NextAction(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("company"),
                            rs.getString("status"), due, rs.getString("next_action_note"), due.isBefore(now));
                })
                .list();

        return new Dashboard(newToday, inbox, activeCount, interviews, overdue, top, funnel(), weeks(now, zone), next);
    }

    private long count(String sql, String name, Object value) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        return (name == null ? spec : spec.param(name, value)).query(Long.class).single();
    }

    /** How many vacancies ever reached each stage or a later one, by status history and current status. */
    private List<FunnelStage> funnel() {
        long[] reached = new long[FUNNEL.size()];
        jdbc.sql("""
                SELECT stage, count(*) AS n FROM (
                    SELECT id, max(CASE s WHEN 'Saved' THEN 1 WHEN 'Applied' THEN 2 WHEN 'Screening' THEN 3 WHEN 'Test task' THEN 3
                                          WHEN 'Interview' THEN 4 WHEN 'Offer' THEN 5 WHEN 'Accepted' THEN 5 ELSE 0 END) AS stage
                    FROM (
                        SELECT id, status AS s FROM vacancy
                        UNION ALL
                        SELECT vacancy_id, to_status FROM status_event
                    ) all_statuses GROUP BY id
                ) per_vacancy WHERE stage > 0 GROUP BY stage
                """)
                .query((rs, i) -> {
                    reached[rs.getInt("stage") - 1] = rs.getLong("n");
                    return null;
                })
                .list();
        List<FunnelStage> out = new ArrayList<>();
        long running = 0;
        for (int i = FUNNEL.size() - 1; i >= 0; i--) {
            running += reached[i];
            out.add(0, new FunnelStage(FUNNEL.get(i), running));
        }
        return out;
    }

    /** Applications per week for the last WEEKS weeks, oldest first, with zeros for empty weeks. */
    private List<Week> weeks(Instant now, ZoneId zone) {
        LocalDate thisMonday = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = thisMonday.minusWeeks(WEEKS - 1L);
        long[] counts = new long[WEEKS];
        jdbc.sql("SELECT applied_at FROM vacancy WHERE applied_at >= :from")
                .param("from", java.sql.Timestamp.from(first.atStartOfDay(zone).toInstant()))
                .query((rs, i) -> {
                    LocalDate day = rs.getTimestamp("applied_at").toInstant().atZone(zone).toLocalDate();
                    int index = (int) java.time.temporal.ChronoUnit.WEEKS.between(first, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                    if (index >= 0 && index < WEEKS) {
                        counts[index]++;
                    }
                    return null;
                })
                .list();
        List<Week> out = new ArrayList<>();
        for (int i = 0; i < WEEKS; i++) {
            out.add(new Week(first.plusWeeks(i), counts[i]));
        }
        return out;
    }
}
