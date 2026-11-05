package com.vacancytracker.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vacancytracker.common.MailSuggestionsEvent;
import com.vacancytracker.common.MailSuggestionsEvent.Item;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class MailSuggestionNotifierTest {

    private final TelegramDigestService telegram = mock(TelegramDigestService.class);
    private final UUID acme = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();
    private final Map<UUID, MailSuggestionNotifier.Vacancy> vacancies = Map.of(
            acme, new MailSuggestionNotifier.Vacancy("Backend Engineer", "Acme"),
            beta, new MailSuggestionNotifier.Vacancy("Data Analyst", "Beta"));

    private MailSuggestionNotifier notifier(boolean flag) {
        return new MailSuggestionNotifier(telegram, (MailSuggestionNotifier.Lookup) ids -> vacancies, flag);
    }

    private static MailSuggestionsEvent event(Item... items) {
        return new MailSuggestionsEvent(List.of(items));
    }

    @Test
    void messageLeadsWithOfferThenInterviewThenRejectionAndNamesVacancyAndCompany() {
        String text = MailSuggestionNotifier.message(List.of(
                new Item("rejection", "Rejected", beta, "Update"),
                new Item("test_task", "Test task", acme, "Task"),
                new Item("interview", "Interview", acme, "Invite"),
                new Item("offer", "Offer", beta, "Offer!")), vacancies);

        assertThat(text).isEqualTo("📬 4 mails need a decision"
                + "\n• Offer — Data Analyst at Beta: move to Offer?"
                + "\n• Interview — Backend Engineer at Acme: move to Interview?"
                + "\n• Rejection — Data Analyst at Beta: move to Rejected?"
                + "\n• Test task — Backend Engineer at Acme: move to Test task?");
    }

    @Test
    void oneMailUsesTheSingularAndAnUnknownVacancyFallsBackToTheCutSubject() {
        String subject = "x".repeat(200) + "\nSecond line <b>bold</b> *md* [link](http://evil)";
        String text = MailSuggestionNotifier.message(List.of(new Item("interview", "Interview", UUID.randomUUID(), subject)), vacancies);

        String[] lines = text.split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).isEqualTo("📬 1 mail needs a decision");
        assertThat(lines[1]).startsWith("• Interview — xxx").endsWith("…: move to Interview?");
        assertThat(lines[1].length()).isLessThan(150);
    }

    @Test
    void fieldsAreOneLineWithoutControlCharactersAndCut() {
        assertThat(MailSuggestionNotifier.cut("a\r\n\tb\u0000  c", 80)).isEqualTo("a b c");
        assertThat(MailSuggestionNotifier.cut("y".repeat(100), 10)).hasSize(10).endsWith("…");
        assertThat(MailSuggestionNotifier.cut(null, 10)).isEmpty();
        String text = MailSuggestionNotifier.message(List.of(new Item("interview", "Interview", null, null)), vacancies);
        assertThat(text).contains("(no subject)");
        assertThat(MailSuggestionNotifier.message(List.of(new Item(null, "Interview", null, "S")), vacancies)).contains("Mail — S");
    }

    @Test
    void aVacancyWithoutACompanyIsNamedByTitleOnly() {
        UUID solo = UUID.randomUUID();
        String text = MailSuggestionNotifier.message(List.of(new Item("offer", "Offer", solo, "S")),
                Map.of(solo, new MailSuggestionNotifier.Vacancy("Designer", " ")));

        assertThat(text).endsWith("Offer — Designer: move to Offer?");
    }

    @Test
    void atMostTenLinesAreListedAndTheRestIsCounted() {
        List<Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < 13; i++) {
            items.add(new Item("interview", "Interview", acme, "S" + i));
        }

        String text = MailSuggestionNotifier.message(items, vacancies);

        assertThat(text.split("\n")).hasSize(1 + 10 + 1);
        assertThat(text).startsWith("📬 13 mails need a decision").endsWith("\n…and 3 more");
    }

    @Test
    void offByDefaultNothingIsSent() {
        when(telegram.isEnabled()).thenReturn(true);
        MailSuggestionNotifier off = notifier(false);

        off.onSuggestions(event(new Item("interview", "Interview", acme, "S")));

        assertThat(off.send(event(new Item("interview", "Interview", acme, "S")))).isFalse();
        verify(telegram, never()).post(anyString());
    }

    @Test
    void nothingIsSentWhenTheDigestIsNotSetUpOrTheEventIsEmpty() {
        when(telegram.isEnabled()).thenReturn(false);
        assertThat(notifier(true).send(event(new Item("interview", "Interview", acme, "S")))).isFalse();

        when(telegram.isEnabled()).thenReturn(true);
        assertThat(notifier(true).send(event())).isFalse();
        notifier(true).onSuggestions(event());
        verify(telegram, never()).post(anyString());
    }

    @Test
    void enabledSendsExactlyOneMessageForTheEvent() {
        when(telegram.isEnabled()).thenReturn(true);
        when(telegram.post(anyString())).thenReturn(null);
        MailSuggestionNotifier on = notifier(true);

        assertThat(on.send(event(new Item("interview", "Interview", acme, "S"), new Item("rejection", "Rejected", beta, "T")))).isTrue();

        verify(telegram).post(org.mockito.ArgumentMatchers.contains("2 mails need a decision"));
    }

    @Test
    void theListenerSendsInTheBackground() {
        when(telegram.isEnabled()).thenReturn(true);
        when(telegram.post(anyString())).thenReturn(null);

        notifier(true).onSuggestions(event(new Item("offer", "Offer", acme, "S")));

        verify(telegram, timeout(5000)).post(org.mockito.ArgumentMatchers.contains("1 mail needs a decision"));
    }

    @Test
    void aTelegramFailureIsReportedAsNotSentAndNeverThrown() {
        when(telegram.isEnabled()).thenReturn(true);
        when(telegram.post(anyString())).thenReturn("Telegram answered HTTP 403");
        assertThat(notifier(true).send(event(new Item("interview", "Interview", acme, "S")))).isFalse();

        when(telegram.post(anyString())).thenThrow(new IllegalStateException("boom 123456:SECRET"));
        assertThat(notifier(true).send(event(new Item("interview", "Interview", acme, "S")))).isFalse();
    }

    @Test
    void aDatabaseFailureWhileLookingUpTheVacanciesIsSwallowed() {
        when(telegram.isEnabled()).thenReturn(true);
        MailSuggestionNotifier.Lookup broken = ids -> {
            throw new QueryTimeoutException("down");
        };

        assertThat(new MailSuggestionNotifier(telegram, broken, true).send(event(new Item("interview", "Interview", acme, "S")))).isFalse();
        verify(telegram, never()).post(anyString());
    }

    @Test
    void mailsWithoutALinkedVacancyNeedNoLookup() {
        when(telegram.isEnabled()).thenReturn(true);
        when(telegram.post(anyString())).thenReturn(null);
        MailSuggestionNotifier noLookup = new MailSuggestionNotifier(telegram, (MailSuggestionNotifier.Lookup) ids -> {
            throw new IllegalStateException("must not be called");
        }, true);

        assertThat(noLookup.send(event(new Item("interview", "Interview", null, "S")))).isTrue();
    }
}
