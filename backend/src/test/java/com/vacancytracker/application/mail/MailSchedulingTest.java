package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.collection.mail.MailParsers;
import com.vacancytracker.vacancy.VacancyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The tick that decides when the mailbox is read, and the interval setting it uses. */
class MailSchedulingTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    private final ImapMailReader reader = mock(ImapMailReader.class);
    private final MailSettings settings = mock(MailSettings.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final CorrespondenceService service = new CorrespondenceService(reader, mock(MailParsers.class),
            mock(CorrespondenceRepository.class), mock(VacancyService.class), clock, "INBOX", settings);

    private void configured(boolean yes, int minutes) {
        when(reader.isConfigured()).thenReturn(yes);
        when(settings.interval()).thenReturn(Duration.ofMinutes(minutes));
        when(settings.intervalMinutes()).thenReturn(minutes);
        when(reader.readFolderDeferred(eq("INBOX"), any(), any())).thenReturn(new ImapMailReader.ReadResult(true, 0, 0, 0, 0, null, List.of()));
    }

    private void reads(int times) {
        verify(reader, times(times)).readFolderDeferred(eq("INBOX"), any(), any());
    }

    @Test
    void theDecisionIsDueOnFirstRunAndOnceAFullIntervalPassed() {
        Duration ten = Duration.ofMinutes(10);
        assertThat(MailSettings.due(null, ten, T0)).isTrue();
        assertThat(MailSettings.due(T0, ten, T0.plusSeconds(599))).isFalse();
        assertThat(MailSettings.due(T0, ten, T0.plusSeconds(600))).isTrue();
        assertThat(MailSettings.due(T0, ten, T0.plusSeconds(3600))).isTrue();
    }

    @Test
    void theFirstTickReadsAndTheNextOnesWaitForTheInterval() {
        configured(true, 30);

        service.scheduledSync();
        reads(1);
        now.set(T0.plus(Duration.ofMinutes(29)));
        service.scheduledSync();
        reads(1);
        now.set(T0.plus(Duration.ofMinutes(30)));
        service.scheduledSync();
        reads(2);
    }

    @Test
    void aShorterIntervalTakesEffectAtTheNextTick() {
        configured(true, 60);
        service.scheduledSync();
        now.set(T0.plus(Duration.ofMinutes(10)));
        service.scheduledSync();
        reads(1);

        when(settings.interval()).thenReturn(Duration.ofMinutes(5));
        service.scheduledSync();
        reads(2);
    }

    @Test
    void withoutAMailboxNothingIsReadAndTheSettingIsNotEvenAsked() {
        configured(false, 15);

        service.scheduledSync();

        reads(0);
        verify(settings, never()).interval();
        assertThat(service.status().nextCheck()).isNull();
    }

    @Test
    void aDatabaseProblemSkipsTheTickInsteadOfFailingIt() {
        when(reader.isConfigured()).thenReturn(true);
        when(settings.interval()).thenThrow(new DataAccessResourceFailureException("down"));

        service.scheduledSync();

        reads(0);
    }

    @Test
    void statusCarriesTheIntervalAndTheNextCheck() {
        configured(true, 45);
        assertThat(service.status().intervalMinutes()).isEqualTo(45);
        assertThat(service.status().nextCheck()).isNull();

        service.scheduledSync();

        assertThat(service.status().nextCheck()).isEqualTo(T0.plus(Duration.ofMinutes(45)));
    }

    @Test
    void onlyTheOfferedStepsAreStored() {
        JdbcClient jdbc = mock(JdbcClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        MailSettings real = new MailSettings(jdbc, Duration.ofMinutes(15));

        for (int bad : new int[] {0, 1, 7, 20, 61, 120, -5}) {
            assertThatThrownBy(() -> real.setIntervalMinutes(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int ok : MailSettings.ALLOWED_MINUTES) {
            assertThat(real.setIntervalMinutes(ok)).isEqualTo(ok);
        }
    }

    @Test
    void aDefaultThatIsNotAStepFallsBackToFifteen() {
        JdbcClient jdbc = mock(JdbcClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(jdbc.sql(any(String.class)).param(any(String.class), any()).query(String.class).optional()).thenReturn(java.util.Optional.empty());

        assertThat(new MailSettings(jdbc, Duration.ofMinutes(7)).intervalMinutes()).isEqualTo(15);
        assertThat(new MailSettings(jdbc, Duration.ofMinutes(30)).intervalMinutes()).isEqualTo(30);
    }
}
