package com.vacancytracker.application.mail;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * One stored job email. suggestedStatus is the status label the mail points to (null for none); suggestionState is
 * none, pending, accepted or dismissed. jobs lists the vacancies of a digest mail (a job alert with several
 * postings); it is empty for any other mail. event is the date and time of the interview or test-task deadline the mail
 * names, or null.
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
        List<DigestJob> jobs,
        Event event) {

    /**
     * The date and time a mail names. With zoneKnown the time is in UTC and serialized with a Z; without, it is the
     * local time as the mail wrote it, serialized with no zone (the owner's own time).
     */
    public record Event(@JsonIgnore LocalDateTime start, boolean zoneKnown, String text) {

        @JsonProperty("startsAt")
        public String startsAt() {
            return zoneKnown ? start.atOffset(ZoneOffset.UTC).toInstant().toString() : start.toString();
        }
    }

    public Correspondence(UUID id, String mailKey, String folder, String sender, String senderName, String subject, Instant receivedAt,
                          String snippet, String category, UUID vacancyId, String linkReason, String suggestedStatus,
                          String suggestionState, Instant createdAt, List<DigestJob> jobs) {
        this(id, mailKey, folder, sender, senderName, subject, receivedAt, snippet, category, vacancyId, linkReason,
                suggestedStatus, suggestionState, createdAt, jobs, null);
    }

    /**
     * One posting listed in a digest mail. company, location and url can be null when the mail does not show them.
     * vacancyId is the vacancy of the Pipeline this posting already is (same canonical URL), or null; it is worked out
     * when the mail is listed and never stored.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DigestJob(String title, String company, String location, String url, UUID vacancyId) {

        public DigestJob(String title, String company, String location, String url) {
            this(title, company, location, url, null);
        }
    }

    public Correspondence withJobs(List<DigestJob> newJobs) {
        return new Correspondence(id, mailKey, folder, sender, senderName, subject, receivedAt, snippet, category, vacancyId,
                linkReason, suggestedStatus, suggestionState, createdAt, newJobs, event);
    }

    public Correspondence {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
