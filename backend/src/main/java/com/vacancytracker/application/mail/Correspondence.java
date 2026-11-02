package com.vacancytracker.application.mail;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored job email. suggestedStatus is the status label the mail points to (null for none); suggestionState is
 * none, pending, accepted or dismissed.
 */
public record Correspondence(
        UUID id,
        String mailKey,
        String folder,
        String sender,
        String senderName,
        String subject,
        Instant receivedAt,
        String snippet,
        String category,
        UUID vacancyId,
        String linkReason,
        String suggestedStatus,
        String suggestionState,
        Instant createdAt) {
}
