package com.vacancytracker.collection.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vacancytracker.collection.mail.ImapMailReader.ReadResult;
import com.vacancytracker.support.AbstractIntegrationTest;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ImapMailReaderTest extends AbstractIntegrationTest {

    private static final String USER = "alerts@example.test";
    private static final String PASSWORD = "s3cret-app-password";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.IMAP)
            .withConfiguration(com.icegreen.greenmail.configuration.GreenMailConfiguration.aConfig().withUser(USER, USER, PASSWORD));

    @Autowired
    private ProcessedMailRepository processed;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE processed_mail");
    }

    private ImapMailReader reader(String password, boolean mark, int max, boolean enabled) {
        return new ImapMailReader(enabled, "127.0.0.1", MAIL.getImap().getPort(), false, USER, password, "INBOX", mark, max, processed);
    }

    private ImapMailReader reader() {
        return reader(PASSWORD, false, 50, true);
    }

    private MimeMessage message(String from, String subject, String text, String html, String messageId) throws MessagingException {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(from);
        m.setRecipients(Message.RecipientType.TO, USER);
        m.setSubject(subject);
        if (html == null) {
            m.setText(text);
        } else {
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(text);
            MimeBodyPart rich = new MimeBodyPart();
            rich.setContent(html, "text/html; charset=utf-8");
            alternative.addBodyPart(plain);
            alternative.addBodyPart(rich);
            m.setContent(alternative);
        }
        m.saveChanges();
        if (messageId != null) {
            m.setHeader("Message-ID", messageId);
        }
        return m;
    }

    private void deliver(MimeMessage m) {
        MAIL.getManagers().getUserManager().getUser(USER).deliver(m);
    }

    private static final class Collector implements MailHandler {
        final List<MailMessage> seen = new ArrayList<>();
        MailHandler.Outcome outcome = MailHandler.Outcome.PROCESSED;

        @Override
        public Outcome handle(MailMessage mail) {
            seen.add(mail);
            return outcome;
        }
    }

    @Test
    void readsEveryMessageOnceWithSenderSubjectAndBothBodies() throws Exception {
        deliver(message("jobs-noreply@linkedin.test", "5 new jobs for you", "Plain body", "<p>Rich body</p>", "<a@x>"));
        deliver(message("alerts@jobs.test", "Second", "Only text", null, "<b@x>"));
        Collector collector = new Collector();

        ReadResult first = reader().readNew(collector);

        assertThat(first).extracting(ReadResult::enabled, ReadResult::seen, ReadResult::handled, ReadResult::error)
                .containsExactly(true, 2, 2, null);
        MailMessage one = collector.seen.get(0);
        assertThat(one.from()).isEqualTo("jobs-noreply@linkedin.test");
        assertThat(one.subject()).isEqualTo("5 new jobs for you");
        assertThat(one.text()).contains("Plain body");
        assertThat(one.html()).contains("<p>Rich body</p>");
        assertThat(one.key()).isEqualTo("<a@x>");
        assertThat(collector.seen.get(1).html()).isNull();

        Collector again = new Collector();
        ReadResult second = reader().readNew(again);

        assertThat(again.seen).isEmpty();
        assertThat(second.handled()).isZero();
        assertThat(second.seen()).isEqualTo(2);
    }

    @Test
    void aMessageTheHandlerAsksToRetryIsOfferedAgain() throws Exception {
        deliver(message("a@x.test", "Later", "Body", null, "<r@x>"));
        Collector collector = new Collector();
        collector.outcome = MailHandler.Outcome.RETRY;

        ReadResult first = reader().readNew(collector);
        collector.outcome = MailHandler.Outcome.PROCESSED;
        ReadResult second = reader().readNew(collector);

        assertThat(first.retried()).isEqualTo(1);
        assertThat(second.handled()).isEqualTo(1);
        assertThat(collector.seen).hasSize(2);
        assertThat(processed.known(List.of("<r@x>"))).containsExactly("<r@x>");
    }

    @Test
    void theSameMessageIdDeliveredTwiceIsHandledOnce() throws Exception {
        deliver(message("a@x.test", "Dup", "Body", null, "<same@x>"));
        deliver(message("a@x.test", "Dup", "Body", null, "<same@x>"));
        Collector collector = new Collector();

        reader().readNew(collector);

        assertThat(collector.seen).hasSize(1);
    }

    @Test
    void aMessageWithoutAnIdGetsAKeyFromItsPosition() throws Exception {
        MimeMessage m = message("a@x.test", "No id", "Body", null, null);
        m.removeHeader("Message-ID");
        deliver(m);
        Collector collector = new Collector();

        reader().readNew(collector);
        reader().readNew(collector);

        assertThat(collector.seen).hasSize(1);
        assertThat(collector.seen.get(0).key()).startsWith("INBOX:");
    }

    @Test
    void processedMessagesAreFlaggedOnTheServerOnlyWhenAsked() throws Exception {
        deliver(message("a@x.test", "Flag me", "Body", null, "<f@x>"));

        reader(PASSWORD, false, 50, true).readNew(new Collector());
        assertThat(flagsOnServer()).doesNotContain(ImapMailReader.PROCESSED_FLAG);

        template.execute("TRUNCATE processed_mail");
        reader(PASSWORD, true, 50, true).readNew(new Collector());
        assertThat(flagsOnServer()).contains(ImapMailReader.PROCESSED_FLAG);
    }

    private String flagsOnServer() throws MessagingException {
        Properties props = new Properties();
        try (Store store = Session.getInstance(props).getStore("imap")) {
            store.connect("127.0.0.1", MAIL.getImap().getPort(), USER, PASSWORD);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Flags flags = inbox.getMessage(1).getFlags();
            String joined = String.join(",", flags.getUserFlags());
            inbox.close(false);
            return joined;
        }
    }

    @Test
    void theNumberOfMessagesPerRunIsLimitedAndTheRestWaits() throws Exception {
        for (int i = 0; i < 5; i++) {
            deliver(message("a@x.test", "Msg " + i, "Body", null, "<m" + i + "@x>"));
        }
        Collector collector = new Collector();

        ReadResult first = reader(PASSWORD, false, 2, true).readNew(collector);
        ReadResult second = reader(PASSWORD, false, 2, true).readNew(collector);
        ReadResult third = reader(PASSWORD, false, 2, true).readNew(collector);

        assertThat(first.handled()).isEqualTo(2);
        assertThat(second.handled()).isEqualTo(2);
        assertThat(third.handled()).isEqualTo(1);
        assertThat(collector.seen).extracting(MailMessage::subject).containsExactly("Msg 0", "Msg 1", "Msg 2", "Msg 3", "Msg 4");
    }

    @Test
    void attachmentsAreIgnored() throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom("a@x.test");
        m.setRecipients(Message.RecipientType.TO, USER);
        m.setSubject("With file");
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText("Real body");
        MimeBodyPart file = new MimeBodyPart();
        file.setText("attachment text");
        file.setDisposition("attachment");
        file.setFileName("notes.txt");
        mixed.addBodyPart(file);
        mixed.addBodyPart(body);
        m.setContent(mixed);
        m.saveChanges();
        deliver(m);
        Collector collector = new Collector();

        reader().readNew(collector);

        assertThat(collector.seen.get(0).text()).isEqualTo("Real body");
    }

    @Test
    void aWrongPasswordIsReportedWithoutThePasswordAndWithoutThrowing() {
        ReadResult result = reader("wrong-password-123", false, 50, true).readNew(new Collector());

        assertThat(result.enabled()).isTrue();
        assertThat(result.error()).isNotNull().doesNotContain("wrong-password-123").doesNotContain(PASSWORD);
        assertThat(result.handled()).isZero();
    }

    @Test
    void aMissingFolderIsAReportedErrorNotACrash() {
        ImapMailReader reader = new ImapMailReader(true, "127.0.0.1", MAIL.getImap().getPort(), false, USER, PASSWORD, "No such folder", false, 50, processed);

        ReadResult result = reader.readNew(new Collector());

        assertThat(result.error()).isNotNull();
    }

    @Test
    void whenDisabledOrUnconfiguredNothingConnects() {
        Collector collector = new Collector();

        assertThat(reader(PASSWORD, false, 50, false).readNew(collector).enabled()).isFalse();
        ImapMailReader noHost = new ImapMailReader(true, "", 993, true, USER, PASSWORD, "INBOX", false, 50, processed);
        assertThat(noHost.isConfigured()).isFalse();
        assertThat(noHost.readNew(collector).enabled()).isFalse();
        assertThat(collector.seen).isEmpty();
    }
}
