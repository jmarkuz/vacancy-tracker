package com.vacancytracker.collection.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FetchProfile;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads alert emails from one IMAP folder of the user's own mailbox (T-064, docs/sources.md Tier 2). It only
 * reads: no message is deleted or moved. A handled message is remembered in the database, and optionally marked
 * with a user flag on the server, so it is never handled twice. Off unless app.mail.imap.enabled is true.
 * The password comes from the environment and is never logged.
 */
@Component
public class ImapMailReader {

    private static final Logger LOG = LoggerFactory.getLogger(ImapMailReader.class);
    static final String PROCESSED_FLAG = "VacancyTrackerProcessed";
    static final int MAX_BODY_CHARS = 2_000_000;
    private static final int TIMEOUT_MS = 60_000;

    /** error is a short text without secrets, or null. */
    public record ReadResult(boolean enabled, int seen, int handled, int retried, int skipped, String error, List<String> handledKeys) {
        static ReadResult disabled() {
            return new ReadResult(false, 0, 0, 0, 0, null, List.of());
        }

        static ReadResult failed(String error) {
            return new ReadResult(true, 0, 0, 0, 0, error, List.of());
        }
    }

    private final boolean enabled;
    private final String host;
    private final int port;
    private final boolean ssl;
    private final String username;
    private final String password;
    private final String folderName;
    private final boolean markProcessed;
    private final int maxPerRun;
    private final int maxAgeDays;
    private final ProcessedMailRepository processed;

    /** Without an age limit: every message of the folder is looked at. */
    public ImapMailReader(boolean enabled, String host, int port, boolean ssl, String username, String password, String folderName,
                          boolean markProcessed, int maxPerRun, ProcessedMailRepository processed) {
        this(enabled, host, port, ssl, username, password, folderName, markProcessed, maxPerRun, 0, processed);
    }

    @Autowired
    public ImapMailReader(@Value("${app.mail.imap.enabled:false}") boolean enabled,
                          @Value("${app.mail.imap.host:}") String host,
                          @Value("${app.mail.imap.port:993}") int port,
                          @Value("${app.mail.imap.ssl:true}") boolean ssl,
                          @Value("${app.mail.imap.username:}") String username,
                          @Value("${app.mail.imap.password:}") String password,
                          @Value("${app.mail.imap.folder:Job alerts}") String folderName,
                          @Value("${app.mail.imap.mark-processed:false}") boolean markProcessed,
                          @Value("${app.mail.imap.max-per-run:50}") int maxPerRun,
                          @Value("${app.mail.imap.max-age-days:14}") int maxAgeDays,
                          ProcessedMailRepository processed) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.username = username;
        this.password = password;
        this.folderName = folderName;
        this.markProcessed = markProcessed;
        this.maxPerRun = Math.max(1, maxPerRun);
        this.maxAgeDays = Math.max(0, maxAgeDays);
        this.processed = processed;
    }

    public boolean isConfigured() {
        return enabled && !host.isBlank() && !username.isBlank();
    }

    /** Offers each unprocessed message of the folder, oldest first, to the handler, up to the per-run limit. */
    public ReadResult readNew(MailHandler handler) {
        return readNew(handler, false);
    }

    /**
     * Like {@link #readNew(MailHandler)}, but the handled messages are not marked processed: their keys come back in
     * the result and the caller marks them with {@link #markProcessed(List)} once what they hold is safely stored.
     */
    public ReadResult readNewDeferred(MailHandler handler) {
        return readNew(handler, true);
    }

    /** Forgets what was handled under the prefix, so the next read offers those messages again. */
    public int forgetProcessed(String keyPrefix) {
        return processed.forgetWithPrefix(keyPrefix);
    }

    public void markProcessed(List<String> keys) {
        keys.forEach(processed::markProcessed);
    }

    /**
     * Reads another folder of the same mailbox for another purpose (the correspondence reader, T-086). keyPrefix
     * keeps its processed state apart from the alert adapters', so one message can serve both. Always read-only and
     * deferred: the keys come back and the caller marks them once what they hold is stored.
     */
    public ReadResult readFolderDeferred(String folder, String keyPrefix, MailHandler handler) {
        return readNew(folder, keyPrefix, false, handler, true);
    }

    private ReadResult readNew(MailHandler handler, boolean defer) {
        return readNew(folderName, "", markProcessed, handler, defer);
    }

    private ReadResult readNew(String folderToRead, String keyPrefix, boolean writable, MailHandler handler, boolean defer) {
        if (!isConfigured()) {
            return ReadResult.disabled();
        }
        Session session = Session.getInstance(properties());
        try (Store store = session.getStore(ssl ? "imaps" : "imap")) {
            store.connect(host, port, username, password);
            try (FolderHandle handle = new FolderHandle(store.getFolder(folderToRead), writable)) {
                return process(handle.folder, keyPrefix, handler, defer);
            }
        } catch (MessagingException e) {
            // The message can mention the host and folder but never the password, which is not part of it.
            LOG.warn("IMAP read failed for folder '{}' on {}: {}", folderToRead, host, e.getMessage());
            return ReadResult.failed("Could not read the mailbox: " + e.getMessage());
        }
    }

    private ReadResult process(Folder folder, String keyPrefix, MailHandler handler, boolean defer) throws MessagingException {
        // Older mail is not even listed: the server searches by date, so a folder of thousands of old messages costs nothing.
        Message[] all = maxAgeDays > 0
                ? folder.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(Instant.now().minus(Duration.ofDays(maxAgeDays)))))
                : folder.getMessages();
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(UIDFolder.FetchProfileItem.UID);
        // Without this each Message-ID is a round trip of its own: minutes for a folder of thousands of messages.
        profile.add("Message-ID");
        folder.fetch(all, profile);

        Map<String, Message> byKey = new LinkedHashMap<>();
        for (Message message : all) {
            byKey.putIfAbsent(keyPrefix + keyOf(folder, message), message);
        }
        var known = processed.known(byKey.keySet());
        List<Map.Entry<String, Message>> pending = byKey.entrySet().stream().filter(e -> !known.contains(e.getKey())).toList();

        int handled = 0;
        int retried = 0;
        int skipped = 0;
        int settled = 0;
        List<String> handledKeys = new java.util.ArrayList<>();
        // The limit counts messages taken, not messages looked at: mail that another sender's adapter will take, or
        // that cannot be read, must not use up the allowance and starve the rest.
        for (Map.Entry<String, Message> entry : pending) {
            if (handled >= maxPerRun) {
                break;
            }
            MailMessage mail;
            try {
                Optional<MailHandler.Outcome> early = handler.preview(toEnvelope(entry.getKey(), (MimeMessage) entry.getValue()));
                if (early.isPresent()) {
                    // Settled from the envelope: the body is never downloaded and the message does not use up the allowance.
                    if (early.get() == MailHandler.Outcome.PROCESSED) {
                        if (defer) {
                            handledKeys.add(entry.getKey());
                        } else {
                            processed.markProcessed(entry.getKey());
                        }
                        flag(folder, entry.getValue());
                        settled++;
                    } else {
                        retried++;
                    }
                    continue;
                }
                mail = toMailMessage(entry.getKey(), (MimeMessage) entry.getValue());
            } catch (IOException | MessagingException e) {
                if (!folder.isOpen()) {
                    // The server dropped the connection (for example a read timeout): every message after this one
                    // would fail at once. Keep what was read so far; the rest is offered again on the next run.
                    LOG.warn("The connection to '{}' was lost after {} messages; stopping this read: {}", folder.getFullName(), handled, e.toString());
                    break;
                }
                LOG.warn("Skipping an unreadable message in '{}': {}", folder.getFullName(), e.toString());
                skipped++;
                continue;
            }
            if (handler.handle(mail) == MailHandler.Outcome.PROCESSED) {
                if (defer) {
                    handledKeys.add(entry.getKey());
                } else {
                    processed.markProcessed(entry.getKey());
                }
                flag(folder, entry.getValue());
                handled++;
            } else {
                retried++;
            }
        }
        return new ReadResult(true, all.length, handled + settled, retried, skipped, null, List.copyOf(handledKeys));
    }

    private void flag(Folder folder, Message message) {
        if (!markProcessed || folder.getMode() != Folder.READ_WRITE) {
            return;
        }
        try {
            if (folder.getPermanentFlags().contains(Flags.Flag.USER) || folder.getPermanentFlags().contains(new Flags(PROCESSED_FLAG))) {
                message.setFlags(new Flags(PROCESSED_FLAG), true);
            }
        } catch (MessagingException e) {
            LOG.debug("Could not flag a processed message: {}", e.getMessage());
        }
    }

    private static String keyOf(Folder folder, Message message) throws MessagingException {
        String[] ids = message.getHeader("Message-ID");
        if (ids != null && ids.length > 0 && !ids[0].isBlank()) {
            return ids[0].trim();
        }
        long uid = folder instanceof UIDFolder u ? u.getUID(message) : message.getMessageNumber();
        long validity = folder instanceof UIDFolder u ? u.getUIDValidity() : 0;
        return folder.getFullName() + ":" + validity + ":" + uid;
    }

    /** Sender, subject and date only, from the envelope fetched for the whole folder: no body is downloaded. */
    private static MailMessage toEnvelope(String key, MimeMessage message) throws MessagingException {
        String from = null;
        String name = null;
        if (message.getFrom() != null && message.getFrom().length > 0) {
            if (message.getFrom()[0] instanceof InternetAddress a) {
                from = a.getAddress();
                name = a.getPersonal();
            } else {
                from = message.getFrom()[0].toString();
            }
        }
        Instant received = message.getReceivedDate() != null ? message.getReceivedDate().toInstant()
                : message.getSentDate() != null ? message.getSentDate().toInstant() : null;
        return new MailMessage(key, from, name, message.getSubject(), received, null, null);
    }

    /** The parts the parsers read, from a message of the mailbox or from an uploaded .eml file. */
    public static MailMessage toMailMessage(String key, MimeMessage message) throws MessagingException, IOException {
        String from = message.getFrom() != null && message.getFrom().length > 0
                ? (message.getFrom()[0] instanceof InternetAddress a ? a.getAddress() : message.getFrom()[0].toString())
                : null;
        String name = message.getFrom() != null && message.getFrom().length > 0 && message.getFrom()[0] instanceof InternetAddress a
                ? a.getPersonal() : null;
        Instant received = message.getReceivedDate() != null ? message.getReceivedDate().toInstant()
                : message.getSentDate() != null ? message.getSentDate().toInstant() : null;
        Bodies bodies = new Bodies();
        collect(message, bodies);
        return new MailMessage(key, from, name, message.getSubject(), received, bodies.text, bodies.html);
    }

    private static final class Bodies {
        String text;
        String html;
    }

    /** Takes the first text and the first HTML part, skipping attachments and anything over the size cap. */
    private static void collect(Part part, Bodies out) throws MessagingException, IOException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, out);
            }
        } else if (part.isMimeType("text/plain") && out.text == null) {
            out.text = cap(part.getContent());
        } else if (part.isMimeType("text/html") && out.html == null) {
            out.html = cap(part.getContent());
        }
    }

    private static String cap(Object content) {
        String s = String.valueOf(content);
        return s.length() > MAX_BODY_CHARS ? s.substring(0, MAX_BODY_CHARS) : s;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "::1".equals(host);
    }

    private Properties properties() {
        Properties props = new Properties();
        String protocol = ssl ? "imaps" : "imap";
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail." + protocol + ".timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail." + protocol + ".peek", "true");
        if (ssl) {
            props.put("mail.imaps.ssl.checkserveridentity", "true");
        } else if (!isLoopback(host)) {
            // Plain IMAP to another machine would send the password in clear text: upgrade or refuse to log in.
            props.put("mail.imap.starttls.enable", "true");
            props.put("mail.imap.starttls.required", "true");
        }
        return props;
    }

    /** Opens a folder read-only unless processed messages are to be flagged; always closes without expunging. */
    private static final class FolderHandle implements AutoCloseable {
        private final Folder folder;

        FolderHandle(Folder folder, boolean writable) throws MessagingException {
            this.folder = folder;
            folder.open(writable ? Folder.READ_WRITE : Folder.READ_ONLY);
        }

        @Override
        public void close() throws MessagingException {
            if (folder.isOpen()) {
                folder.close(false);
            }
        }
    }
}
