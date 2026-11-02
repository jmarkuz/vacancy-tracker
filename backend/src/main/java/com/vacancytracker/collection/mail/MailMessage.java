package com.vacancytracker.collection.mail;

import java.time.Instant;

/** One alert email as the parsers see it. Either body can be null. Attachments are never read. */
public record MailMessage(String key, String from, String subject, Instant receivedAt, String text, String html) {
}
