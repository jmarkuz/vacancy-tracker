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
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * Status changes that still stand: the one just before an "undo" entry was taken back, so it must not count as a
     * stage reached.
     */
    static final String EFFECTIVE_EVENTS = """
            SELECT vacancy_id, to_status, at FROM (
                SELECT vacancy_id, to_status, at, lead(note) OVER (PARTITION BY vacancy_id ORDER BY at, CASE WHEN note = '%1$s' THEN 1 ELSE 0 END, id) AS next_note
                FROM status_event
            ) e WHERE next_note IS DISTINCT FROM '%1$s'""".formatted(BulkStatusService.UNDO_NOTE);
    /** The statuses in which the ball is with the employer: an answer is awaited. */
    static final List<String> WAITING = List.of("Applied", "Screening", "Interview");
    static final int FOLLOW_UPS = 5;
    static final int TOP = 5;
    static final int NEXT_ACTIONS = 5;

    public record TopMatch(UUID id, String title, String company, int score) {
    }

    public record NextAction(UUID id, String title, String company, String status, Instant dueAt, String note, boolean overdue) {
    }

    /** An application that has been quiet: no status change and no mail for quietDays days. */
    public record FollowUp(UUID id, String title, String company, String status, Instant lastActivityAt, long quietDays) {
    }

    public record FunnelStage(String status, long count) {
    }

    public record Week(LocalDate start, long applications) {
    }

    public record Dashboard(long newToday, long inbox, long active, long interviews, long overdue,
                            List<TopMatch> topMatches, List<FunnelStage> funnel, List<Week> applicationsPerWeek,
                            List<NextAction> nextActions, List<FollowUp> followUps) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int quietDays;

    public DashboardService(JdbcClient jdbc, Clock clock, @Value("${app.followup.quiet-days:10}") int quietDays) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.quietDays = Math.max(1, quietDays);
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

        return new Dashboard(newToday, inbox, activeCount, interviews, overdue, top, funnel(), weeks(now, zone), next, followUps(now));
    }

    /**
     * Applications waiting for an answer that have been quiet for quietDays: the last status change and the last mail
     * linked to the vacancy are both older than that. A vacancy with a next action planned for later is left out: the
     * owner has already decided when to look at it.
     */
    private List<FollowUp> followUps(Instant now) {
        Instant limit = now.minus(quietDays, java.time.temporal.ChronoUnit.DAYS);
        return jdbc.sql("""
                        SELECT id, title, company, status, last_activity FROM (
                            SELECT v.id, v.title, v.company, v.status,
                                   GREATEST(COALESCE((SELECT max(e.at) FROM status_event e WHERE e.vacancy_id = v.id), v.first_seen_at),
                                            COALESCE((SELECT max(c.received_at) FROM correspondence c WHERE c.vacancy_id = v.id), v.first_seen_at)) AS last_activity
                            FROM vacancy v
                            WHERE v.status = ANY (:waiting) AND (v.next_action_at IS NULL OR v.next_action_at <= :now)
                        ) q WHERE last_activity <= :limit ORDER BY last_activity, id LIMIT :n
                        """)
                .param("waiting", WAITING.toArray(String[]::new)).param("now", java.sql.Timestamp.from(now))
                .param("limit", java.sql.Timestamp.from(limit)).param("n", FOLLOW_UPS)
                .query((rs, i) -> {
                    Instant last = rs.getTimestamp("last_activity").toInstant();
                    return new FollowUp(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("company"),
                            rs.getString("status"), last, java.time.temporal.ChronoUnit.DAYS.between(last, now));
                })
                .list();
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
                        SELECT vacancy_id, to_status FROM (%s) effective
                    ) all_statuses GROUP BY id
                ) per_vacancy WHERE stage > 0 GROUP BY stage
                """.formatted(EFFECTIVE_EVENTS))
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
        // The day a vacancy first reached Applied, from its status history (the vacancy row has no such column in use).
        jdbc.sql("SELECT min(at) AS applied_at FROM (" + EFFECTIVE_EVENTS + ") effective WHERE to_status = 'Applied' GROUP BY vacancy_id HAVING min(at) >= :from")
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
