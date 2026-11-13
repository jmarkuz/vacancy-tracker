package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.collection.mail.MailMessage;
import com.vacancytracker.collection.mail.MailParsers;
import com.vacancytracker.vacancy.VacancyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The date of an interview is read from the whole mail when it is stored, and for a stored mail seen again. */
class CorrespondenceEventTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-05T09:00:00Z");

    private final CorrespondenceRepository repository = mock(CorrespondenceRepository.class);
    private final CorrespondenceService service = new CorrespondenceService(mock(ImapMailReader.class), new MailParsers(List.of()),
            repository, mock(VacancyService.class), Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC), "INBOX",
            mock(MailSettings.class));

    @AfterEach
    void stop() {
        service.shutdown();
    }

    private static MailMessage mail(String key, String subject, String text, Instant received) {
        return new MailMessage(key, "hr@acme.example", subject, received, text, null);
    }

    private Correspondence stored() {
        ArgumentCaptor<Correspondence> stored = ArgumentCaptor.forClass(Correspondence.class);
        verify(repository).insert(stored.capture());
        return stored.getValue();
    }

    @Test
    void anInterviewMailIsStoredWithItsEvent() {
        service.handle(mail("<e1@x>", "Interview invitation", "Hello! Your interview is on Thursday, 12 November at 14:30 CET.", RECEIVED), "INBOX");

        Correspondence.Event event = stored().event();
        assertThat(event.start()).isEqualTo(LocalDateTime.parse("2026-11-12T13:30"));
        assertThat(event.zoneKnown()).isTrue();
        assertThat(event.text()).isEqualTo("12 November at 14:30 CET");
        assertThat(event.startsAt()).isEqualTo("2026-11-12T13:30:00Z");
    }

    @Test
    void aLocalTimeIsStoredWithoutAZoneAndSerializedWithoutOne() {
        service.handle(mail("<e2@x>", "Interview", "The call is on 12.11.2026 at 14:30", RECEIVED), "INBOX");

        Correspondence.Event event = stored().event();
        assertThat(event.zoneKnown()).isFalse();
        assertThat(event.startsAt()).isEqualTo("2026-11-12T14:30");
    }

    @Test
    void theDateIsReadFromTheWholeMailNotOnlyTheStoredText() {
        String text = "Hello!\n" + "Filler sentence about the interview process. ".repeat(150) + "\nThe interview is on 12 November 2026 at 14:30.";
        assertThat(text.length()).isGreaterThan(CorrespondenceService.SNIPPET);

        service.handle(mail("<e3@x>", "Interview", text, RECEIVED), "INBOX");

        Correspondence c = stored();
        assertThat(c.snippet()).doesNotContain("12 November");
        assertThat(c.event().start()).isEqualTo(LocalDateTime.parse("2026-11-12T14:30"));
    }

    @Test
    void theSubjectCountsAndARelativeDayIsCountedFromTheReceivedDay() {
        service.handle(mail("<e4@x>", "Interview tomorrow at 10:00", "See you there.", RECEIVED), "INBOX");

        assertThat(stored().event().start()).isEqualTo(LocalDateTime.parse("2026-11-06T10:00"));
    }

    @Test
    void aMailWithoutADateTheClockIsTheReference() {
        service.handle(mail("<e5@x>", "Interview", "Interview tomorrow at 10:00", null), "INBOX");

        assertThat(stored().event().start()).isEqualTo(LocalDateTime.parse("2026-11-06T10:00"));
    }

    @Test
    void aTestTaskDeadlineIsAnEvent() {
        service.handle(mail("<e6@x>", "Test task", "Please send the test task by Friday, 13 November 2026 at 18:00", RECEIVED), "INBOX");

        assertThat(stored().event().start()).isEqualTo(LocalDateTime.parse("2026-11-13T18:00"));
    }

    @Test
    void aMailWithoutADateHasNoEvent() {
        service.handle(mail("<e7@x>", "Interview", "We would like to invite you to an interview, please send your times.", RECEIVED), "INBOX");

        assertThat(stored().event()).isNull();
    }

    @Test
    void otherCategoriesAreNotSearchedForADate() {
        service.handle(mail("<e8@x>", "Application received", "Thank you for applying to this position. We call on 12 November 2026 at 14:30", RECEIVED),
                "INBOX");

        assertThat(stored().event()).isNull();
    }

    @Test
    void aStoredMailWithoutAnEventGetsOneWhenItIsSeenAgain() {
        UUID id = UUID.randomUUID();
        MailMessage mail = mail("<e9@x>", "Interview", "The interview is on 12 November 2026 at 14:30 UTC", RECEIVED);
        Correspondence old = new Correspondence(id, mail.key(), "INBOX", "hr@acme.example", null, "Interview", RECEIVED, "text", "interview", null, null,
                null, "none", Instant.parse("2026-11-05T10:00:00Z"), List.of());
        when(repository.findByKey(mail.key())).thenReturn(Optional.of(old));

        CorrespondenceService.Outcome outcome = service.handle(mail, "INBOX");

        assertThat(outcome.stored()).isFalse();
        ArgumentCaptor<Correspondence.Event> event = ArgumentCaptor.forClass(Correspondence.Event.class);
        verify(repository).updateEvent(org.mockito.ArgumentMatchers.eq(id), event.capture());
        assertThat(event.getValue().start()).isEqualTo(LocalDateTime.parse("2026-11-12T14:30"));
        assertThat(event.getValue().zoneKnown()).isTrue();
        verify(repository, never()).insert(any());
    }

    @Test
    void aStoredMailThatHasItsEventIsLeftAlone() {
        MailMessage mail = mail("<e10@x>", "Interview", "The interview is on 12 November 2026 at 14:30", RECEIVED);
        Correspondence has = new Correspondence(UUID.randomUUID(), mail.key(), "INBOX", "hr@acme.example", null, "Interview", RECEIVED, "text",
                "interview", null, null, null, "none", RECEIVED, List.of(), new Correspondence.Event(LocalDateTime.parse("2026-11-12T14:30"), false, "x"));
        when(repository.findByKey(mail.key())).thenReturn(Optional.of(has));

        service.handle(mail, "INBOX");

        verify(repository, never()).updateEvent(any(), any());
    }

    @Test
    void aStoredMailWithNoDateInItStaysWithoutAnEvent() {
        MailMessage mail = mail("<e11@x>", "Interview", "Please send your times.", RECEIVED);
        Correspondence old = new Correspondence(UUID.randomUUID(), mail.key(), "INBOX", "hr@acme.example", null, "Interview", RECEIVED, "text",
                "interview", null, null, null, "none", RECEIVED, List.of());
        when(repository.findByKey(mail.key())).thenReturn(Optional.of(old));

        service.handle(mail, "INBOX");

        verify(repository, never()).updateEvent(any(), any());
    }
}
