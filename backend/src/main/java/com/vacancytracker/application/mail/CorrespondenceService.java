package com.vacancytracker.application.mail;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.collection.mail.MailHandler;
import com.vacancytracker.collection.mail.MailMessage;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.StatusPath;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancyStatus;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers of employers and other job mail (T-086, T-087, ADR-043). The reader goes through the configured folders of
 * the owner's mailbox (read-only, through the existing IMAP reader, with its own processed state), keeps only
 * job-related mail, links each one to the application it is about and, when the mail moves the application on (a
 * rejection, an invitation, a test task, an offer, a confirmation), suggests the status. The owner accepts or
 * dismisses the suggestion; nothing changes a status by itself. A mail can also be imported as an .eml file.
 */
@Service
public class CorrespondenceService {

    private static final Logger LOG = LoggerFactory.getLogger(CorrespondenceService.class);
    static final String KEY_PREFIX = "reply:";
    static final int SNIPPET = 600;
    static final String LINKED_BY_YOU = "Linked by you.";
    static final String SAME_SENDER = "Same sender as a mail you linked.";
    static final int MAX_EML_BYTES = 5 * 1024 * 1024;
    private static final Map<VacancyStatus, Integer> STAGE = Map.of(VacancyStatus.NEW, 0, VacancyStatus.SAVED, 0,
            VacancyStatus.APPLIED, 1, VacancyStatus.SCREENING, 2, VacancyStatus.INTERVIEW, 3, VacancyStatus.TEST_TASK, 3,
            VacancyStatus.OFFER, 4, VacancyStatus.ACCEPTED, 5);

    /** An application a mail can be linked to by hand. */
    public record ApplicationOption(UUID id, String title, String company, String status) {
    }

    /** What a sync did: how many mails were read, kept as job mail, linked, and given a suggestion. */
    public record SyncResult(boolean configured, int seen, int stored, int linked, int suggested, String error) {
    }

    /** What happened to one mail: stored (with its record) or skipped with the reason. */
    public record Outcome(boolean stored, String reason, Correspondence correspondence) {
    }

    public record Status(boolean configured, List<String> folders, long pending, Instant lastSync, String lastError) {
    }

    private final ImapMailReader reader;
    private final CorrespondenceRepository repository;
    private final VacancyService vacancies;
    private final Clock clock;
    private final List<String> folders;
    private volatile Instant lastSync;
    private volatile String lastError;

    public CorrespondenceService(ImapMailReader reader, CorrespondenceRepository repository, VacancyService vacancies, Clock clock,
                                 @Value("${app.mail.replies.folders:INBOX}") String folders) {
        this.reader = reader;
        this.repository = repository;
        this.vacancies = vacancies;
        this.clock = clock;
        this.folders = Arrays.stream(folders.split(",")).map(String::strip).filter(f -> !f.isEmpty()).toList();
    }

    public Status status() {
        return new Status(reader.isConfigured(), folders, repository.pendingCount(), lastSync, lastError);
    }

    @Scheduled(initialDelayString = "${app.mail.replies.initial-delay:PT1M}", fixedDelayString = "${app.mail.replies.interval:PT15M}")
    void scheduledSync() {
        if (reader.isConfigured()) {
            sync();
        }
    }

    /** Reads every configured folder now. Never throws for a mailbox problem: the result carries the error. */
    public synchronized SyncResult sync() {
        if (!reader.isConfigured()) {
            return new SyncResult(false, 0, 0, 0, 0, null);
        }
        int seen = 0;
        int stored = 0;
        int linked = 0;
        int suggested = 0;
        String error = null;
        for (String folder : folders) {
            List<Outcome> outcomes = new ArrayList<>();
            MailHandler handler = mail -> {
                outcomes.add(handle(mail, folder));
                return MailHandler.Outcome.PROCESSED;
            };
            ImapMailReader.ReadResult result = reader.readFolderDeferred(folder, KEY_PREFIX, handler);
            if (result.error() != null) {
                error = result.error();
                continue;
            }
            reader.markProcessed(result.handledKeys());
            seen += result.seen();
            for (Outcome o : outcomes) {
                if (o.stored()) {
                    stored++;
                    linked += o.correspondence().vacancyId() == null ? 0 : 1;
                    suggested += "pending".equals(o.correspondence().suggestionState()) ? 1 : 0;
                }
            }
        }
        lastSync = clock.instant();
        lastError = error;
        if (stored > 0) {
            LOG.info("Mail: {} job mails stored, {} linked to applications, {} status suggestions", stored, linked, suggested);
        }
        return new SyncResult(true, seen, stored, linked, suggested, error);
    }

    /** One mail: classified, and stored with its link and suggestion when it is about jobs. */
    // No @Transactional: sync() and importEml() call this method on the same bean, so Spring's proxy would never see
    // the call. One mail is stored by a single INSERT ... ON CONFLICT DO NOTHING, which is atomic by itself and keeps
    // a repeated delivery of the same mail from being stored twice.
    public Outcome handle(MailMessage mail, String folder) {
        MailCategory category = MailClassifier.classify(mail);
        if (category == MailCategory.NOT_JOB) {
            return new Outcome(false, "not about jobs", null);
        }
        if (category == MailCategory.JOB_ALERT) {
            // Alerts become vacancies through the alert sources (ADR-030), not correspondence.
            return new Outcome(false, "a job alert", null);
        }
        Optional<Correspondence> known = repository.findByKey(mail.key());
        if (known.isPresent()) {
            return new Outcome(false, "already stored", known.get());
        }
        Optional<MailLinker.Link> link = MailLinker.link(mail, repository.candidates());
        if (link.isEmpty() && mail.from() != null && !MailLinker.sharedSender(mail.from())) {
            // A reply from a person the owner already tied to an application stays with it, whatever its wording.
            link = repository.manualLinkFor(mail.from()).map(v -> new MailLinker.Link(v, 0, SAME_SENDER));
        }
        Suggestion s = suggestion(category, link.map(MailLinker.Link::vacancyId).orElse(null));
        Correspondence c = new Correspondence(UUID.randomUUID(), mail.key(), folder, mail.from(), mail.senderName(),
                mail.subject(), mail.receivedAt(), MailClassifier.snippet(mail, SNIPPET), category.code(),
                link.map(MailLinker.Link::vacancyId).orElse(null), link.map(MailLinker.Link::reason).orElse(null), s.status(), s.state(),
                clock.instant());
        repository.insert(c);
        return new Outcome(true, category.code(), c);
    }

    private record Suggestion(String status, String state) {
    }

    /** A suggestion only when the mail moves a linked application forward, or ends it, through allowed steps. */
    private Suggestion suggestion(MailCategory category, UUID vacancyId) {
        VacancyStatus target = category.suggests();
        if (vacancyId == null || target == null) {
            return new Suggestion(null, "none");
        }
        VacancyStatus current = vacancies.get(vacancyId).getStatus();
        boolean forward = target == VacancyStatus.REJECTED
                ? STAGE.getOrDefault(current, -1) >= 1 && current != VacancyStatus.ACCEPTED
                : target != current && STAGE.containsKey(current) && STAGE.get(target) >= STAGE.get(current);
        if (!forward || StatusPath.between(current, target).isEmpty()) {
            return new Suggestion(null, "none");
        }
        return new Suggestion(target.label(), "pending");
    }

    /** Imports one mail from an .eml file, as if it had arrived in the mailbox. */
    public Outcome importEml(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_EML_BYTES) {
            throw new IllegalArgumentException("Upload an .eml file of at most 5 MB.");
        }
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
            String[] ids = message.getHeader("Message-ID");
            String key = KEY_PREFIX + (ids != null && ids.length > 0 && !ids[0].isBlank() ? ids[0].trim() : "eml:" + sha256(bytes));
            return handle(ImapMailReader.toMailMessage(key, message), "imported");
        } catch (MessagingException | IOException e) {
            throw new IllegalArgumentException("The file is not a readable email (.eml).");
        }
    }

    public List<Correspondence> list(UUID vacancyId, boolean pendingOnly) {
        return repository.list(vacancyId, pendingOnly, 200);
    }

    /**
     * Removes stored mails from this app only: the mailbox is not touched. A mail read from the mailbox is not stored
     * again, because its key stays marked as handled.
     */
    public int delete(List<UUID> ids) {
        return repository.deleteAll(ids == null ? List.of() : ids.stream().distinct().toList());
    }

    /** Removes the stored mails older than this many days. */
    public int deleteOlderThan(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("days must be at least 1");
        }
        return repository.deleteBefore(clock.instant().minus(java.time.Duration.ofDays(days)));
    }

    public Correspondence get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("Mail not found: " + id));
    }

    /** Applies the suggested status through allowed steps, noting the mail in the history. */
    @Transactional
    public Vacancy accept(UUID id) {
        Correspondence c = get(id);
        if (!"pending".equals(c.suggestionState()) || c.vacancyId() == null) {
            throw new IllegalArgumentException("This mail has no open suggestion.");
        }
        Vacancy v = vacancies.moveTo(c.vacancyId(), VacancyStatus.fromLabel(c.suggestedStatus()),
                "from email: " + (c.subject() == null ? "(no subject)" : c.subject()));
        repository.updateState(id, "accepted");
        return v;
    }

    /** The applications the owner can link a mail to by hand. */
    public List<ApplicationOption> applications() {
        return repository.applications();
    }

    public Correspondence dismiss(UUID id) {
        get(id);
        repository.updateState(id, "dismissed");
        return get(id);
    }

    /** The owner links the mail to another vacancy (or none); the suggestion is worked out again. */
    @Transactional
    public Correspondence relink(UUID id, UUID vacancyId) {
        Correspondence c = get(id);
        if (vacancyId != null) {
            vacancies.get(vacancyId);
        }
        Suggestion s = suggestion(MailCategory.of(c.category()), vacancyId);
        repository.updateLink(id, vacancyId, vacancyId == null ? null : LINKED_BY_YOU, s.status(), s.state());
        if (vacancyId != null && c.sender() != null && !MailLinker.sharedSender(c.sender())) {
            // The sender is now known to write about this application: earlier mail from them belongs here too.
            for (Correspondence other : repository.unlinkedFrom(c.sender())) {
                Suggestion o = suggestion(MailCategory.of(other.category()), vacancyId);
                repository.updateLink(other.id(), vacancyId, SAME_SENDER, o.status(), o.state());
            }
        }
        return get(id);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return new String(bytes, 0, Math.min(32, bytes.length), StandardCharsets.US_ASCII);
        }
    }
}
