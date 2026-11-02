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
}
