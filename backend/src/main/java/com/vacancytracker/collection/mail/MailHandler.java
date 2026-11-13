package com.vacancytracker.collection.mail;

import java.util.Optional;

/** Consumes one alert email. It should not throw; a message it cannot handle yet is answered with RETRY. */
@FunctionalInterface
public interface MailHandler {

    enum Outcome {
        /** Done, or deliberately skipped: the message is marked as processed and never offered again. */
        PROCESSED,
        /** Not handled; it is offered again on the next read. */
        RETRY
    }

    Outcome handle(MailMessage mail);

    /**
     * Decides from the envelope alone (sender, subject, date; no body) what to do with a message, so a message the
     * handler does not need is never downloaded. Empty means the full message is needed and handle is called.
     */
    default Optional<Outcome> preview(MailMessage envelope) {
        return Optional.empty();
    }
}
