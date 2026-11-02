package com.vacancytracker.collection.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vacancytracker.collection.adapter.AdapterContractTest;
import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class EmailAlertAdapterTest extends AdapterContractTest {

    private static final String USER = "alerts@example.test";
    private static final String PASSWORD = "app-password-1";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(USER, USER, PASSWORD));

    @Autowired
    private ProcessedMailRepository processed;

    @Autowired
    private LinkedInAlertParser linkedIn;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE processed_mail");
    }

    private SourceAdapter adapter(String password, boolean enabled) {
        ImapMailReader reader = new ImapMailReader(enabled, "127.0.0.1", MAIL.getImap().getPort(), false, USER, password,
                "INBOX", false, 50, processed);
        return new LinkedInAlertAdapter(linkedIn, new MailParsers(java.util.List.of(linkedIn)), reader);
    }

    @Override
    protected SourceAdapter healthyAdapter() {
        return adapter(PASSWORD, true);
    }

    @Override
    protected SourceAdapter failingAdapter() {
        return adapter("wrong-password", true);
    }

    private void deliver(String from, String subject, String html, String id) throws MessagingException, IOException {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(from);
        m.setRecipients(Message.RecipientType.TO, USER);
        m.setSubject(subject);
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart rich = new MimeBodyPart();
        rich.setContent(html, "text/html; charset=utf-8");
        alternative.addBodyPart(rich);
        m.setContent(alternative);
        m.saveChanges();
        m.setHeader("Message-ID", id);
        MAIL.getManagers().getUserManager().getUser(USER).deliver(m);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/mail", name), StandardCharsets.UTF_8);
    }

    @Test
    void turnsAlertEmailsIntoPostingsOnceAndIgnoresOtherSenders() throws Exception {
        deliver("jobalerts-noreply@linkedin.com", "5 new jobs", fixture("linkedin-alert.html"), "<li1@x>");
        deliver("news@unknown-sender.test", "Weekly digest", fixture("other-sender.html"), "<other@x>");
        SourceAdapter adapter = adapter(PASSWORD, true);

        FetchOutcome first = adapter.fetch();

        assertThat(first.postings()).hasSize(2);
        RawPosting p = first.postings().get(0);
        assertThat(p.adapter()).isEqualTo("email-linkedin");
        assertThat(p.externalId()).isEqualTo("3912345678");
        assertThat(p.url()).isEqualTo("https://www.linkedin.com/jobs/view/3912345678/");
        assertThat(p.title()).isEqualTo("Example Role Alpha");
        assertThat(p.company()).isEqualTo("Northwind Labs");
        assertThat(p.location()).isEqualTo("Prague, Czechia");
        assertThat(p.postedAt()).isNotNull();
        assertThat(processed.known(java.util.List.of("<li1@x>", "<other@x>")))
                .as("nothing is marked until the pipeline has stored the vacancies").isEmpty();
        assertThat(adapter.fetch().postings()).as("so a failed store offers the mails again").hasSize(2);

        first.onStored().run();

        assertThat(processed.known(java.util.List.of("<li1@x>", "<other@x>"))).hasSize(2);
        assertThat(adapter.fetch().postings()).isEmpty();
    }

    @Test
    void withoutImapSettingsItDoesNothing() throws Exception {
        deliver("jobalerts-noreply@linkedin.com", "5 new jobs", fixture("linkedin-alert.html"), "<li2@x>");

        FetchOutcome outcome = adapter(PASSWORD, false).fetch();

        assertThat(outcome.postings()).isEmpty();
        assertThat(processed.known(java.util.List.of("<li2@x>"))).isEmpty();
    }

    @Test
    void anEmailFromAnotherKnownSenderIsLeftForThatSendersAdapter() throws Exception {
        MailParser other = new MailParser() {
            @Override
            public String adapterId() {
                return "email-other";
            }

            @Override
            public boolean accepts(MailMessage mail) {
                return MailParser.fromDomain(mail.from(), "other.test");
            }

            @Override
            public java.util.List<ParsedJob> parse(MailMessage mail) {
                return java.util.List.of();
            }
        };
        deliver("a@other.test", "For the other adapter", "<p>x</p>", "<o1@x>");
        ImapMailReader reader = new ImapMailReader(true, "127.0.0.1", MAIL.getImap().getPort(), false, USER, PASSWORD, "INBOX", false, 50, processed);
        SourceAdapter linkedInOnly = new LinkedInAlertAdapter(linkedIn, new MailParsers(java.util.List.of(linkedIn, other)), reader);

        linkedInOnly.fetch();

        assertThat(processed.known(java.util.List.of("<o1@x>"))).isEmpty();
    }

    @Test
    void allSendersAdaptersTogetherHandleOneMailboxAndSkipUnknownMail() throws Exception {
        deliver("jobalerts-noreply@linkedin.com", "LinkedIn", fixture("linkedin-alert.html"), "<a1@x>");
        deliver("noreply@jobs.cz", "Jobs.cz", fixture("jobscz-alert.html"), "<a2@x>");
        deliver("hello@startupjobs.cz", "StartupJobs", fixture("startupjobs-alert.html"), "<a3@x>");
        deliver("alert@indeed.com", "Indeed", fixture("indeed-alert.html"), "<a4@x>");
        deliver("newsletter@unknown-sender.test", "Unknown", "<p>Hello</p>", "<a5@x>");
        ImapMailReader reader = new ImapMailReader(true, "127.0.0.1", MAIL.getImap().getPort(), false, USER, PASSWORD, "INBOX", false, 50, processed);
        MailParsers all = new MailParsers(java.util.List.of(linkedIn, new JobsCzAlertParser(), new StartupJobsAlertParser(), new IndeedAlertParser()));
        java.util.List<SourceAdapter> adapters = java.util.List.of(
                new LinkedInAlertAdapter(linkedIn, all, reader), new JobsCzAlertAdapter(new JobsCzAlertParser(), all, reader),
                new StartupJobsAlertAdapter(new StartupJobsAlertParser(), all, reader), new IndeedAlertAdapter(new IndeedAlertParser(), all, reader));

        java.util.Map<String, java.util.List<RawPosting>> byAdapter = new java.util.LinkedHashMap<>();
        for (SourceAdapter adapter : adapters) {
            FetchOutcome outcome = adapter.fetch();
            byAdapter.put(adapter.id(), outcome.postings());
            outcome.onStored().run();
        }

        assertThat(byAdapter.get("email-linkedin")).hasSize(2);
        assertThat(byAdapter.get("email-jobscz")).hasSize(2);
        assertThat(byAdapter.get("email-startupjobs")).hasSize(2);
        assertThat(byAdapter.get("email-indeed")).hasSize(2);
        assertThat(byAdapter.values().stream().flatMap(java.util.List::stream).map(RawPosting::adapter).distinct()).hasSize(4);
        assertThat(processed.known(java.util.List.of("<a1@x>", "<a2@x>", "<a3@x>", "<a4@x>", "<a5@x>"))).hasSize(5);
        assertThat(adapters.stream().mapToInt(a -> a.fetch().postings().size()).sum()).isZero();
    }
}
