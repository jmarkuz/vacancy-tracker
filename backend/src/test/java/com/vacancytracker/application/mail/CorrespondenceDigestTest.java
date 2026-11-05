package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.collection.mail.LinkedInAlertParser;
import com.vacancytracker.collection.mail.MailMessage;
import com.vacancytracker.collection.mail.MailParser;
import com.vacancytracker.collection.mail.MailParsers;
import com.vacancytracker.collection.mail.ParsedJob;
import com.vacancytracker.vacancy.VacancyService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A job alert with several postings is stored with the postings read out of it (the Mail screen lists them one by one). */
class CorrespondenceDigestTest {

    private static final String LINKEDIN = "jobs-noreply@linkedin.com";

    private final CorrespondenceRepository repository = mock(CorrespondenceRepository.class);
    private final CorrespondenceService service = new CorrespondenceService(mock(ImapMailReader.class),
            new MailParsers(List.of(new LinkedInAlertParser())), repository, mock(VacancyService.class),
            Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC), "INBOX");

    @AfterEach
    void stop() {
        service.shutdown();
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/mail", name), StandardCharsets.UTF_8);
    }

    /** The text says "position" and "resume", so the mail counts as job mail; the html is the alert with two postings. */
    private static MailMessage digest(String key, String from, String html) {
        return new MailMessage(key, from, "Hello", Instant.parse("2026-11-05T09:00:00Z"), "About the position and your resume", html);
    }

    @Test
    void storesThePostingsOfADigestWithTheirTitleCompanyAndPlace() throws IOException {
        service.handle(digest("<d1@x>", LINKEDIN, fixture("linkedin-alert.html")), "INBOX");

        ArgumentCaptor<Correspondence> stored = ArgumentCaptor.forClass(Correspondence.class);
        verify(repository).insert(stored.capture());
        assertThat(stored.getValue().jobs()).containsExactly(
                new Correspondence.DigestJob("Example Role Alpha", "Northwind Labs", "Prague, Czechia", "https://www.linkedin.com/jobs/view/3912345678/"),
                new Correspondence.DigestJob("Senior Example Role", "Vantage Lane", "Remote", "https://www.linkedin.com/jobs/view/3987654321/"));
    }

    @Test
    void aMailFromAnUnknownSenderHasNoPostings() throws IOException {
        service.handle(digest("<d2@x>", "hr@acme.example", fixture("linkedin-alert.html")), "INBOX");

        ArgumentCaptor<Correspondence> stored = ArgumentCaptor.forClass(Correspondence.class);
        verify(repository).insert(stored.capture());
        assertThat(stored.getValue().jobs()).isEmpty();
    }

    @Test
    void onePostingIsNotADigest() {
        String one = "<a href=\"https://www.linkedin.com/comm/jobs/view/3912345678/?trk=x\">Backend Engineer</a><p>Acme</p><p>Berlin</p>";

        assertThat(service.digestJobs(digest("<d3@x>", LINKEDIN, one))).isEmpty();
    }

    @Test
    void aLongDigestIsCutAtTheLimit() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < CorrespondenceService.MAX_DIGEST_JOBS + 10; i++) {
            html.append("<div><a href=\"https://www.linkedin.com/jobs/view/").append(4_000_000_000L + i).append("/\">Role ").append(i)
                    .append("</a><p>Company ").append(i).append("</p><p>City</p></div>");
        }

        assertThat(service.digestJobs(digest("<d4@x>", LINKEDIN, html.toString()))).hasSize(CorrespondenceService.MAX_DIGEST_JOBS);
    }

    @Test
    void aParserThatFailsNeverFailsTheMail() {
        MailParser broken = mock(MailParser.class);
        when(broken.accepts(any())).thenReturn(true);
        when(broken.parse(any())).thenThrow(new IllegalStateException("template changed"));
        CorrespondenceService fragile = new CorrespondenceService(mock(ImapMailReader.class), new MailParsers(List.of(broken)), repository,
                mock(VacancyService.class), Clock.systemUTC(), "INBOX");
        try {
            assertThat(fragile.digestJobs(digest("<d5@x>", LINKEDIN, "<p>x</p>"))).isEmpty();
        } finally {
            fragile.shutdown();
        }
    }

    @Test
    void aStoredDigestWithoutPostingsGetsThemWhenItIsSeenAgain() throws IOException {
        UUID id = UUID.randomUUID();
        MailMessage mail = digest("<d6@x>", LINKEDIN, fixture("linkedin-alert.html"));
        Correspondence old = new Correspondence(id, mail.key(), "INBOX", LINKEDIN, null, "Hello", mail.receivedAt(), "text", "other_job", null, null,
                null, "none", Instant.parse("2026-11-05T10:00:00Z"), List.of());
        when(repository.findByKey(mail.key())).thenReturn(Optional.of(old));

        CorrespondenceService.Outcome outcome = service.handle(mail, "INBOX");

        assertThat(outcome.stored()).isFalse();
        ArgumentCaptor<List<Correspondence.DigestJob>> jobs = ArgumentCaptor.forClass(List.class);
        verify(repository).updateJobs(eq(id), jobs.capture());
        assertThat(jobs.getValue()).hasSize(2);
        verify(repository, never()).insert(any());
    }

    @Test
    void parsedJobsKeepTheirOrder() {
        assertThat(new ParsedJob("1", "u", "t", null, null).title()).isEqualTo("t");
    }
}
