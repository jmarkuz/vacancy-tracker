package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.collection.mail.MailHandler;
import com.vacancytracker.collection.mail.MailMessage;
import com.vacancytracker.collection.mail.MailParsers;
import com.vacancytracker.common.MailSuggestionsEvent;
import com.vacancytracker.vacancy.VacancyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** A mailbox read announces the mails it stored with a pending suggestion: once per read, never the ones seen again. */
class CorrespondenceNotifyTest {

    private final ImapMailReader reader = mock(ImapMailReader.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CorrespondenceService service = spy(new CorrespondenceService(reader, mock(MailParsers.class),
            mock(CorrespondenceRepository.class), mock(VacancyService.class),
            Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC), "INBOX", mock(MailSettings.class)));

    private static MailMessage mail(String key) {
        return new MailMessage(key, "hr@acme.test", "Subject " + key, Instant.parse("2026-11-03T09:00:00Z"), "text", null);
    }

    private static CorrespondenceService.Outcome stored(String key, String category, String status, String state, UUID vacancy) {
        Correspondence c = new Correspondence(UUID.randomUUID(), key, "INBOX", "hr@acme.test", null, "Subject " + key, null, "text",
                category, vacancy, null, status, state, Instant.parse("2026-11-03T12:00:00Z"), List.of());
        return new CorrespondenceService.Outcome(true, category, c);
    }

    private void readMails(String... keys) {
        when(reader.isConfigured()).thenReturn(true);
        when(reader.readFolderDeferred(eq("INBOX"), any(), any())).thenAnswer(call -> {
            MailHandler handler = call.getArgument(2);
            for (String key : keys) {
                handler.handle(mail(key));
            }
            return new ImapMailReader.ReadResult(true, keys.length, keys.length, 0, 0, null, List.of());
        });
    }

    @Test
    void oneEventPerReadListsOnlyStoredMailsWithAPendingSuggestion() {
        UUID vacancy = UUID.randomUUID();
        service.setEvents(events);
        readMails("a", "b", "c", "d");
        doReturn(stored("a", "interview", "Interview", "pending", vacancy)).when(service).handle(eq(mail("a")), any());
        doReturn(stored("b", "rejection", "Rejected", "pending", vacancy)).when(service).handle(eq(mail("b")), any());
        doReturn(stored("c", "recruiter_message", null, "none", null)).when(service).handle(eq(mail("c")), any());
        doReturn(new CorrespondenceService.Outcome(false, "already stored", null)).when(service).handle(eq(mail("d")), any());

        CorrespondenceService.SyncResult result = service.sync();

        ArgumentCaptor<MailSuggestionsEvent> captured = ArgumentCaptor.forClass(MailSuggestionsEvent.class);
        verify(events).publishEvent(captured.capture());
        assertThat(captured.getValue().items()).extracting(MailSuggestionsEvent.Item::category).containsExactly("interview", "rejection");
        assertThat(captured.getValue().items().get(0).status()).isEqualTo("Interview");
        assertThat(captured.getValue().items().get(0).vacancyId()).isEqualTo(vacancy);
        assertThat(result.stored()).isEqualTo(3);
        assertThat(result.suggested()).isEqualTo(2);
    }

    @Test
    void mailsSeenAgainOrWithoutASuggestionPublishNothing() {
        service.setEvents(events);
        readMails("a", "b");
        doReturn(new CorrespondenceService.Outcome(false, "already stored", null)).when(service).handle(eq(mail("a")), any());
        doReturn(stored("b", "offer", "Offer", "none", UUID.randomUUID())).when(service).handle(eq(mail("b")), any());

        service.sync();

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void withoutAPublisherTheReadStillWorks() {
        readMails("a");
        doReturn(stored("a", "interview", "Interview", "pending", null)).when(service).handle(eq(mail("a")), any());

        assertThat(service.sync().suggested()).isEqualTo(1);
    }
}
