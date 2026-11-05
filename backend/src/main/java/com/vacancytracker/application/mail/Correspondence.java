package com.vacancytracker.application.mail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One stored job email. suggestedStatus is the status label the mail points to (null for none); suggestionState is
 * none, pending, accepted or dismissed. jobs lists the vacancies of a digest mail (a job alert with several
 * postings); it is empty for any other mail.
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
        Instant createdAt,
        List<DigestJob> jobs) {

    /** One posting listed in a digest mail. company, location and url can be null when the mail does not show them. */
    public record DigestJob(String title, String company, String location, String url) {
    }

    public Correspondence {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
