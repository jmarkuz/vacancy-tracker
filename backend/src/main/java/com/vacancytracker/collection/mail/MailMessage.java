package com.vacancytracker.collection.mail;

import java.time.Instant;

/**
 * One email as the parsers see it. Either body can be null. Attachments are never read. senderName is the display
 * name of the From address ("Acme Recruiting"), or null; the correspondence reader uses it to find the company.
 */
public record MailMessage(String key, String from, String senderName, String subject, Instant receivedAt, String text, String html) {

    public MailMessage(String key, String from, String subject, Instant receivedAt, String text, String html) {
        this(key, from, null, subject, receivedAt, text, html);
    }
}
