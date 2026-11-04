package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.vacancy.VacancyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The mailbox read in the background: it answers at once, runs one read at a time, and reports its result. */
class CorrespondenceSyncTest {

    private final ImapMailReader reader = mock(ImapMailReader.class);
    private final CorrespondenceRepository repository = mock(CorrespondenceRepository.class);
    private final CorrespondenceService service = new CorrespondenceService(reader, repository, mock(VacancyService.class),
            Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC), "INBOX");

    @AfterEach
    void stop() {
        service.shutdown();
    }

    private static ImapMailReader.ReadResult read(int seen, String error) {
        return new ImapMailReader.ReadResult(true, seen, seen, 0, 0, error, List.of());
    }

    private void waitUntilIdle() throws InterruptedException {
        for (int i = 0; i < 200 && service.status().syncing(); i++) {
            Thread.sleep(25);
        }
        assertThat(service.status().syncing()).isFalse();
    }

    @Test
    void withoutAMailboxNothingStartsAndTheReasonSaysWhy() {
        when(reader.isConfigured()).thenReturn(false);

        CorrespondenceService.SyncStart start = service.startSync();

        assertThat(start.started()).isFalse();
        assertThat(start.reason()).contains("not set up");
        assertThat(service.status().syncing()).isFalse();
    }

    @Test
    void aStartedReadRunsInTheBackgroundAndLeavesItsResult() throws Exception {
        when(reader.isConfigured()).thenReturn(true);
        when(reader.readFolderDeferred(eq("INBOX"), any(), any())).thenReturn(read(4, null));

        CorrespondenceService.SyncStart start = service.startSync();
        waitUntilIdle();

        assertThat(start.started()).isTrue();
        CorrespondenceService.Status status = service.status();
        assertThat(status.lastResult()).isNotNull();
        assertThat(status.lastResult().seen()).isEqualTo(4);
        assertThat(status.lastResult().error()).isNull();
        assertThat(status.lastSync()).isEqualTo(Instant.parse("2026-11-03T12:00:00Z"));
    }

    @Test
    void aSecondStartWhileOneRunsIsRefusedAndTheFirstStillFinishes() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.isConfigured()).thenReturn(true);
        when(reader.readFolderDeferred(eq("INBOX"), any(), any())).thenAnswer(call -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return read(1, null);
        });

        assertThat(service.startSync().started()).isTrue();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        CorrespondenceService.SyncStart second = service.startSync();

        assertThat(second.started()).isFalse();
        assertThat(second.reason()).contains("already running");
        assertThat(service.status().syncing()).isTrue();
        release.countDown();
        waitUntilIdle();
        assertThat(service.status().lastResult().seen()).isEqualTo(1);
    }

    @Test
    void aDirectSyncWhileAReadRunsIsRefusedAndKeepsTheFlag() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.isConfigured()).thenReturn(true);
        when(reader.readFolderDeferred(eq("INBOX"), any(), any())).thenAnswer(call -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return read(1, null);
        });

        assertThat(service.startSync().started()).isTrue();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        CorrespondenceService.SyncResult direct = service.sync();

        assertThat(direct.error()).contains("already running");
        assertThat(service.status().syncing()).isTrue();
        release.countDown();
        waitUntilIdle();
    }

    @Test
    void aMailboxErrorIsReportedNotThrown() throws Exception {
        when(reader.isConfigured()).thenReturn(true);
        when(reader.readFolderDeferred(eq("INBOX"), any(), any())).thenReturn(read(0, "Login failed"));

        service.startSync();
        waitUntilIdle();

        assertThat(service.status().lastError()).isEqualTo("Login failed");
        assertThat(service.status().lastResult().error()).isEqualTo("Login failed");
    }
}
