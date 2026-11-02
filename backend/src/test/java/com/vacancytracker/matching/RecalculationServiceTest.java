package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.matching.MatchService.MatchSession;
import com.vacancytracker.matching.RecalculationService.Progress;
import com.vacancytracker.matching.RecalculationService.State;
import com.vacancytracker.support.MutableClock;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Concurrency rules of the recalculation job, without a database and without relying on timing. */
class RecalculationServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final MatchService matching = mock(MatchService.class);
    private final VacancyRepository vacancies = mock(VacancyRepository.class);

    private List<UUID> ids(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private Progress waitForCompleted(RecalculationService service) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Progress p = service.progress();
            if (p.state() == State.COMPLETED) {
                return p;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The job did not finish in time");
    }

    @Test
    void oneJobAtATimeAndARequestDuringTheJobQueuesExactlyOneMorePass() throws InterruptedException {
        when(vacancies.findAllIds()).thenReturn(ids(3));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MatchSession blocking = id -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
        MatchSession quick = id -> List.of();
        when(matching.session()).thenReturn(blocking, quick);
        RecalculationService service = new RecalculationService(matching, vacancies, clock);

        RecalculationService.Started first = service.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        RecalculationService.Started second = service.start();
        RecalculationService.Started third = service.start();

        assertThat(first.started()).isTrue();
        assertThat(second.started()).isFalse();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(third.started()).isFalse();

        release.countDown();
        verify(matching, timeout(5000).times(2)).session();
        Progress last = waitForCompleted(service);
        assertThat(last.processed()).isEqualTo(3);
        assertThat(last.jobId()).isNotEqualTo(first.jobId());
        service.shutdown();
    }

    @Test
    void aFailingVacancyIsCountedAndDoesNotStopTheJob() throws InterruptedException {
        List<UUID> ids = ids(4);
        when(vacancies.findAllIds()).thenReturn(ids);
        MatchSession flaky = id -> {
            if (id.equals(ids.get(1))) {
                throw new IllegalArgumentException("bad stored profile");
            }
            return List.of();
        };
        when(matching.session()).thenReturn(flaky);
        RecalculationService service = new RecalculationService(matching, vacancies, clock);

        service.start();
        Progress done = waitForCompleted(service);

        assertThat(done.total()).isEqualTo(4);
        assertThat(done.processed()).isEqualTo(4);
        assertThat(done.failed()).isEqualTo(1);
        service.shutdown();
    }

    @Test
    void progressIsNotFoundBeforeTheFirstJobAndAfterwardsStartsAFreshJob() throws InterruptedException {
        when(vacancies.findAllIds()).thenReturn(ids(1));
        when(matching.session()).thenReturn(id -> List.of());
        RecalculationService service = new RecalculationService(matching, vacancies, clock);

        assertThatThrownBy(service::progress).isInstanceOf(NotFoundException.class);

        RecalculationService.Started first = service.start();
        waitForCompleted(service);
        RecalculationService.Started second = service.start();

        assertThat(first.started()).isTrue();
        assertThat(second.started()).isTrue();
        assertThat(second.jobId()).isNotEqualTo(first.jobId());
        verify(matching, timeout(5000).times(2)).session();
        service.shutdown();
    }
}
