package com.vacancytracker.collection.mail;

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
}
