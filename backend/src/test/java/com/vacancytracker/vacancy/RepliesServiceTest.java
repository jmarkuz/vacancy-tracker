package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.RepliesService.Replies;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The reply statistics against a real database with a fixed clock: Berlin, 5 Nov 2026 12:00 local (11:00 UTC). */
@SpringBootTest
class RepliesServiceTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-05T11:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired
    private JdbcTemplate template;

    @Autowired
    private JdbcClient client;

    private RepliesService service() {
        return new RepliesService(client, Clock.fixed(NOW, BERLIN));
    }

    private UUID application(String status, String appliedAt) {
        UUID id = UUID.randomUUID();
        template.update("INSERT INTO vacancy (id, title, company, status, first_seen_at, last_seen_at) VALUES (?, 'Dev', 'Acme', ?, ?, ?)",
                id, status, Timestamp.from(NOW), Timestamp.from(NOW));
        if (appliedAt != null) {
            template.update("INSERT INTO status_event (id, vacancy_id, to_status, at) VALUES (?, ?, 'Applied', ?)",
                    UUID.randomUUID(), id, Timestamp.from(Instant.parse(appliedAt)));
        }
        return id;
    }

    private void mail(UUID vacancy, String category, String receivedAt) {
        template.update("INSERT INTO correspondence (id, mail_key, received_at, category, vacancy_id, suggestion_state, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'none', now())",
                UUID.randomUUID(), UUID.randomUUID().toString(), Timestamp.from(Instant.parse(receivedAt)), category, vacancy);
    }

    @Test
    void noApplicationsGivesZerosAndNoMedian() {
        Replies r = service().replies(30);

        assertThat(r.applied()).isZero();
        assertThat(r.replied()).isZero();
        assertThat(r.medianDays()).isNull();
        assertThat(r.outcomes()).isEqualTo(new RepliesService.Outcomes(0, 0, 0, 0, 0));
    }

    @Test
    void applicationsWithoutMailsHaveNoReplies() {
        application("Applied", "2026-10-30T10:00:00Z");

        Replies r = service().replies(30);

        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.replied()).isZero();
        assertThat(r.medianDays()).isNull();
    }

    @Test
    void medianOfAnOddNumberOfRepliesIsTheMiddleOne() {
        for (int days : new int[] {1, 3, 10}) {
            UUID v = application("Applied", "2026-10-20T10:00:00Z");
            mail(v, "other_job", Instant.parse("2026-10-20T10:00:00Z").plusSeconds(days * 86400L).toString());
        }

        Replies r = service().replies(30);

        assertThat(r.replied()).isEqualTo(3);
        assertThat(r.medianDays()).isEqualTo(3.0);
    }

    @Test
    void medianOfAnEvenNumberOfRepliesIsTheMeanOfTheMiddleTwo() {
        for (int days : new int[] {1, 2, 4, 9}) {
            UUID v = application("Applied", "2026-10-15T10:00:00Z");
            mail(v, "interview", Instant.parse("2026-10-15T10:00:00Z").plusSeconds(days * 86400L).toString());
        }

        assertThat(service().replies(30).medianDays()).isEqualTo(3.0);
    }

    @Test
    void theFirstMailOfAnApplicationDecidesTheDelayAndLaterMailsCountAsOutcomes() {
        UUID v = application("Interview", "2026-10-20T10:00:00Z");
        mail(v, "application_viewed", "2026-10-22T10:00:00Z");
        mail(v, "interview", "2026-10-25T10:00:00Z");

        Replies r = service().replies(30);

        assertThat(r.replied()).isEqualTo(1);
        assertThat(r.medianDays()).isEqualTo(2.0);
        assertThat(r.outcomes().interview()).isEqualTo(1);
        assertThat(r.outcomes().other()).isEqualTo(1);
    }

    @Test
    void mailsBeforeTheApplyDateAndUnlinkedMailsDoNotCount() {
        UUID v = application("Applied", "2026-10-20T10:00:00Z");
        mail(v, "rejection", "2026-10-19T10:00:00Z");
        mail(v, "rejection", "2026-10-20T10:00:00Z");
        template.update("INSERT INTO correspondence (id, mail_key, received_at, category, suggestion_state, created_at)"
                + " VALUES (?, 'unlinked', '2026-10-25T10:00:00Z', 'offer', 'none', now())", UUID.randomUUID());

        Replies r = service().replies(30);

        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.replied()).isZero();
        assertThat(r.medianDays()).isNull();
        assertThat(r.outcomes()).isEqualTo(new RepliesService.Outcomes(0, 0, 0, 0, 0));
    }

    @Test
    void everyCategoryIsCountedUnderItsOutcome() {
        UUID v = application("Offer", "2026-10-20T10:00:00Z");
        for (String c : new String[] {"interview", "interview", "test_task", "offer", "rejection", "recruiter_message", "other_job"}) {
            mail(v, c, "2026-10-21T10:00:00Z");
        }

        assertThat(service().replies(30).outcomes()).isEqualTo(new RepliesService.Outcomes(2, 1, 1, 1, 2));
    }

    @Test
    void thePeriodStartsAtMidnightOfTheFirstDayInTheOwnersZone() {
        // 30 days ending today (5 Nov) start on 7 Oct 00:00 Berlin = 6 Oct 22:00 UTC.
        application("Applied", "2026-10-06T21:59:00Z");
        application("Applied", "2026-10-06T22:00:00Z");

        assertThat(service().replies(30).applied()).isEqualTo(1);
        assertThat(service().replies(90).applied()).isEqualTo(2);
    }

    @Test
    void anApplicationInTheFutureIsLeftOut() {
        application("Applied", "2026-11-05T11:00:01Z");

        assertThat(service().replies(30).applied()).isZero();
    }

    @Test
    void aVacancyThatMovedOnStillCountsOnceFromTheFirstAppliedDate() {
        UUID v = application("Interview", "2026-10-20T10:00:00Z");
        template.update("INSERT INTO status_event (id, vacancy_id, to_status, at) VALUES (?, ?, 'Interview', ?)",
                UUID.randomUUID(), v, Timestamp.from(Instant.parse("2026-10-28T10:00:00Z")));

        assertThat(service().replies(30).applied()).isEqualTo(1);
    }

    @Test
    void aVacancyThatNeverReachedAppliedIsNotCounted() {
        application("Saved", null);

        assertThat(service().replies(30).applied()).isZero();
    }

    @Test
    void daysOutsideTheAllowedRangeAreRejected() {
        assertThatThrownBy(() -> service().replies(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().replies(366)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownOrMissingCategoryIsOther() {
        assertThat(RepliesService.bucket(null)).isEqualTo(4);
        assertThat(RepliesService.bucket("something_new")).isEqualTo(4);
    }
}
