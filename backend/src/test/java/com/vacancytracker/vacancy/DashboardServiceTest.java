package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.DashboardService.Dashboard;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DashboardServiceTest extends AbstractIntegrationTest {

    @Autowired
    private DashboardService service;

    @Autowired
    private JdbcTemplate template;

    private UUID vacancy(String title, String status, Integer score, Instant firstSeen, Instant nextAction, Instant appliedAt) {
        UUID id = UUID.randomUUID();
        template.update("INSERT INTO vacancy (id, title, company, status, match_score, first_seen_at, last_seen_at, next_action_at,"
                        + " next_action_note) VALUES (?, ?, 'Acme', ?, ?, ?, ?, ?, ?)",
                id, title, status, score, java.sql.Timestamp.from(firstSeen), java.sql.Timestamp.from(firstSeen),
                nextAction == null ? null : java.sql.Timestamp.from(nextAction), nextAction == null ? null : "Do " + title);
        if (appliedAt != null) {
            // The week counts come from the status history: the day the vacancy first reached Applied.
            template.update("INSERT INTO status_event (id, vacancy_id, to_status, at) VALUES (?, ?, 'Applied', ?)",
                    UUID.randomUUID(), id, java.sql.Timestamp.from(appliedAt));
        }
        return id;
    }

    private void event(UUID id, String to) {
        template.update("INSERT INTO status_event (id, vacancy_id, to_status, at) VALUES (?, ?, ?, now())", UUID.randomUUID(), id, to);
    }

    @Test
    void anEmptyDatabaseGivesZerosAndEmptyLists() {
        Dashboard d = service.dashboard();

        assertThat(d.inbox()).isZero();
        assertThat(d.newToday()).isZero();
        assertThat(d.active()).isZero();
        assertThat(d.topMatches()).isEmpty();
        assertThat(d.nextActions()).isEmpty();
        assertThat(d.funnel()).extracting(DashboardService.FunnelStage::count).containsOnly(0L);
        assertThat(d.applicationsPerWeek()).hasSize(8).extracting(DashboardService.Week::applications).containsOnly(0L);
    }

    @Test
    void kpisCountTheRightVacancies() {
        Instant now = Instant.now();
        Instant old = now.minus(10, ChronoUnit.DAYS);
        vacancy("fresh", "New", 90, now, null, null);
        vacancy("old new", "New", 50, old, null, null);
        vacancy("saved", "Saved", null, old, now.plus(2, ChronoUnit.DAYS), null);
        vacancy("applied late", "Applied", null, old, now.minus(1, ChronoUnit.DAYS), now);
        vacancy("interview", "Interview", null, old, null, now);
        vacancy("rejected late", "Rejected", null, old, now.minus(3, ChronoUnit.DAYS), null);
        vacancy("hidden", "Hidden", null, now, null, null);

        Dashboard d = service.dashboard();

        assertThat(d.inbox()).isEqualTo(2);
        assertThat(d.newToday()).isBetween(1L, 2L);
        assertThat(d.active()).isEqualTo(3);
        assertThat(d.interviews()).isEqualTo(1);
        assertThat(d.overdue()).as("only active vacancies can be overdue").isEqualTo(1);
    }

    @Test
    void topMatchesAreTheBestScoredNewOnesAndNextActionsComeInDueOrder() {
        Instant now = Instant.now();
        for (int i = 0; i < 7; i++) {
            vacancy("job " + i, "New", 10 + i * 10, now, null, null);
        }
        vacancy("saved high", "Saved", 100, now, now.plus(5, ChronoUnit.DAYS), null);
        vacancy("overdue", "Applied", null, now, now.minus(2, ChronoUnit.DAYS), null);
        vacancy("soon", "Screening", null, now, now.plus(1, ChronoUnit.DAYS), null);

        Dashboard d = service.dashboard();

        assertThat(d.topMatches()).extracting(DashboardService.TopMatch::score).containsExactly(70, 60, 50, 40, 30);
        assertThat(d.nextActions()).extracting(DashboardService.NextAction::title).containsExactly("overdue", "soon", "saved high");
        assertThat(d.nextActions().get(0).overdue()).isTrue();
        assertThat(d.nextActions().get(1).overdue()).isFalse();
        assertThat(d.nextActions().get(0).note()).isEqualTo("Do overdue");
    }

    @Test
    void theFunnelCountsEveryVacancyThatReachedAStageOrLater() {
        Instant now = Instant.now();
        UUID a = vacancy("a", "Rejected", null, now, null, null);
        event(a, "Saved");
        event(a, "Applied");
        event(a, "Rejected");
        UUID b = vacancy("b", "Interview", null, now, null, null);
        event(b, "Applied");
        event(b, "Screening");
        event(b, "Interview");
        vacancy("c", "Offer", null, now, null, null);
        vacancy("d", "New", null, now, null, null);

        assertThat(service.dashboard().funnel()).extracting(DashboardService.FunnelStage::status, DashboardService.FunnelStage::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Saved", 3L),
                        org.assertj.core.groups.Tuple.tuple("Applied", 3L),
                        org.assertj.core.groups.Tuple.tuple("Screening", 2L),
                        org.assertj.core.groups.Tuple.tuple("Interview", 2L),
                        org.assertj.core.groups.Tuple.tuple("Offer", 1L));
    }

    @Test
    void applicationsPerWeekUseTheLastEightWeeksOldestFirst() {
        Instant now = Instant.now();
        vacancy("this week", "Applied", null, now, null, now);
        vacancy("also this week", "Applied", null, now, null, now);
        vacancy("long ago", "Applied", null, now, null, now.minus(200, ChronoUnit.DAYS));
        vacancy("last week", "Applied", null, now, null, now.minus(7, ChronoUnit.DAYS));

        var weeks = service.dashboard().applicationsPerWeek();

        assertThat(weeks).hasSize(8);
        assertThat(weeks.get(7).applications()).isEqualTo(2);
        assertThat(weeks.get(6).applications()).isEqualTo(1);
        assertThat(weeks.stream().mapToLong(DashboardService.Week::applications).sum()).isEqualTo(3);
        assertThat(weeks.get(0).start()).isBefore(weeks.get(7).start());
    }

    @Test
    void aStageTakenBackByUndoIsNotCounted() {
        Instant now = Instant.now();
        UUID id = vacancy("undone", "Saved", null, now, null, null);
        template.update("INSERT INTO status_event (id, vacancy_id, to_status, at) VALUES (?, ?, 'Applied', ?)",
                UUID.randomUUID(), id, java.sql.Timestamp.from(now));  // the same instant as the undo entry

        template.update("INSERT INTO status_event (id, vacancy_id, from_status, to_status, at, note) VALUES (?, ?, 'Applied', 'Saved', ?, ?)",
                UUID.randomUUID(), id, java.sql.Timestamp.from(now), BulkStatusService.UNDO_NOTE);

        Dashboard d = service.dashboard();

        assertThat(d.funnel()).extracting(DashboardService.FunnelStage::status, DashboardService.FunnelStage::count)
                .contains(org.assertj.core.groups.Tuple.tuple("Saved", 1L), org.assertj.core.groups.Tuple.tuple("Applied", 0L));
        assertThat(d.applicationsPerWeek().stream().mapToLong(DashboardService.Week::applications).sum()).isZero();
    }

    private void statusEventAt(UUID id, String to, Instant at) {
        template.update("INSERT INTO status_event (id, vacancy_id, to_status, at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), id, to, java.sql.Timestamp.from(at));
    }

    private void mailAt(UUID id, Instant at) {
        template.update("INSERT INTO correspondence (id, mail_key, category, vacancy_id, suggestion_state, received_at, created_at)"
                + " VALUES (?, ?, 'recruiter_message', ?, 'none', ?, now())", UUID.randomUUID(), "k-" + UUID.randomUUID(), id,
                java.sql.Timestamp.from(at));
    }

    @Test
    void anApplicationQuietForTenDaysIsListedOldestFirstWithHowLongItHasBeenQuiet() {
        Instant now = Instant.now();
        UUID a = vacancy("quiet 12", "Applied", null, now.minus(30, ChronoUnit.DAYS), null, null);
        statusEventAt(a, "Applied", now.minus(12, ChronoUnit.DAYS));
        UUID b = vacancy("quiet 20", "Screening", null, now.minus(30, ChronoUnit.DAYS), null, null);
        statusEventAt(b, "Screening", now.minus(20, ChronoUnit.DAYS));

        var followUps = service.dashboard().followUps();

        assertThat(followUps).extracting(DashboardService.FollowUp::title).containsExactly("quiet 20", "quiet 12");
        assertThat(followUps.get(0).quietDays()).isEqualTo(20);
        assertThat(followUps.get(1).quietDays()).isEqualTo(12);
        assertThat(followUps.get(0).status()).isEqualTo("Screening");
    }

    @Test
    void recentActivityOrAPlannedNextActionKeepsAnApplicationOffTheList() {
        Instant now = Instant.now();
        Instant long_ago = now.minus(40, ChronoUnit.DAYS);
        UUID recentStatus = vacancy("recent status", "Applied", null, long_ago, null, null);
        statusEventAt(recentStatus, "Applied", now.minus(3, ChronoUnit.DAYS));
        UUID recentMail = vacancy("recent mail", "Applied", null, long_ago, null, null);
        statusEventAt(recentMail, "Applied", now.minus(30, ChronoUnit.DAYS));
        mailAt(recentMail, now.minus(2, ChronoUnit.DAYS));
        UUID planned = vacancy("planned", "Applied", null, long_ago, now.plus(3, ChronoUnit.DAYS), null);
        statusEventAt(planned, "Applied", now.minus(30, ChronoUnit.DAYS));
        UUID overduePlan = vacancy("plan overdue", "Applied", null, long_ago, now.minus(1, ChronoUnit.DAYS), null);
        statusEventAt(overduePlan, "Applied", now.minus(30, ChronoUnit.DAYS));

        assertThat(service.dashboard().followUps()).extracting(DashboardService.FollowUp::title).containsExactly("plan overdue");
    }

    @Test
    void onlyApplicationsWaitingForAnAnswerAreListed() {
        Instant now = Instant.now();
        Instant long_ago = now.minus(40, ChronoUnit.DAYS);
        for (String status : new String[] {"New", "Saved", "Test task", "Offer", "Accepted", "Rejected", "Withdrawn", "Dismissed"}) {
            vacancy("not waiting " + status, status, null, long_ago, null, null);
        }
        vacancy("interview", "Interview", null, long_ago, null, null);

        assertThat(service.dashboard().followUps()).extracting(DashboardService.FollowUp::title).containsExactly("interview");
    }

    @Test
    void aVacancyWithoutAnyHistoryIsQuietSinceItWasFirstSeen() {
        Instant now = Instant.now();
        vacancy("imported as applied", "Applied", null, now.minus(15, ChronoUnit.DAYS), null, null);
        vacancy("just imported", "Applied", null, now.minus(2, ChronoUnit.DAYS), null, null);

        assertThat(service.dashboard().followUps()).extracting(DashboardService.FollowUp::title).containsExactly("imported as applied");
    }

    @Test
    void theListHoldsAtMostFiveAndAnEmptyDatabaseHasNone() {
        assertThat(service.dashboard().followUps()).isEmpty();
        Instant long_ago = Instant.now().minus(60, ChronoUnit.DAYS);
        for (int i = 0; i < 8; i++) {
            vacancy("quiet " + i, "Applied", null, long_ago.plus(i, ChronoUnit.DAYS), null, null);
        }

        assertThat(service.dashboard().followUps()).hasSize(DashboardService.FOLLOW_UPS);
    }
}
