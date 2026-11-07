package com.vacancytracker.common;

import java.util.List;
import java.util.UUID;

/**
 * Published after a mailbox read that stored new mails carrying a pending status suggestion (M8). Only mails stored by
 * this read are listed, so a mail seen again is never announced twice. The mail body is not part of the event.
 * It lives in common so the mail tracker (application) can announce it and notification can listen without either
 * package depending on the other.
 */
public record MailSuggestionsEvent(List<Item> items) {

    /** category is the mail category code (for example "interview"); status is the suggested status label. */
    public record Item(String category, String status, UUID vacancyId, String subject) {
    }

    public MailSuggestionsEvent {
        items = List.copyOf(items);
    }
}
