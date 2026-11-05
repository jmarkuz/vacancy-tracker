package com.vacancytracker.application.mail;

import com.vacancytracker.collection.mail.ImapMailReader;
import com.vacancytracker.collection.mail.MailHandler;
import com.vacancytracker.collection.mail.MailMessage;
import com.vacancytracker.collection.mail.MailParsers;
import com.vacancytracker.collection.mail.ParsedJob;
import com.vacancytracker.common.CanonicalUrl;
import com.vacancytracker.common.MailSuggestionsEvent;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.ImportedPosting;
import com.vacancytracker.vacancy.StatusPath;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancyStatus;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
    /**
     * How much of a mail's readable text is stored (the column is TEXT). The Mail card folds a long text and unfolds it
     * with "Show more", so the stored text must be long enough for that to show real content (a typical recruiter
     * message fits in 4000 characters); the full mailbox message stays in the mailbox. Mails stored earlier keep the
     * shorter text they were stored with.
     */
    static final int SNIPPET = 4000;
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

    /** syncing is true while a read of the mailbox runs; lastResult is what the last one did (null before the first). */
    public record Status(boolean configured, List<String> folders, long pending, Instant lastSync, String lastError,
                         boolean syncing, SyncResult lastResult, int intervalMinutes,
                         Instant nextCheck) {
    }

    /** started is false when a read was already running or the mailbox is not set up. */
    public record SyncStart(boolean started, String reason) {
    }

    private final ImapMailReader reader;
    private final MailParsers parsers;
    private final CorrespondenceRepository repository;
    private final VacancyService vacancies;
    private final Clock clock;
    private final List<String> folders;
    private final MailSettings settings;
    private ApplicationEventPublisher events;
    private volatile Instant lastSync;
    private volatile String lastError;
    private volatile SyncResult lastResult;
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-sync");
        t.setDaemon(true);
        return t;
    });

    public CorrespondenceService(ImapMailReader reader, MailParsers parsers, CorrespondenceRepository repository, VacancyService vacancies,
                                 Clock clock, @Value("${app.mail.replies.folders:INBOX}") String folders, MailSettings settings) {
        this.reader = reader;
        this.parsers = parsers;
        this.repository = repository;
        this.vacancies = vacancies;
        this.clock = clock;
        this.settings = settings;
        this.folders = Arrays.stream(folders.split(",")).map(String::strip).filter(f -> !f.isEmpty()).toList();
    }

    /** Setter injection keeps the constructor as it was; without a publisher (plain unit tests) nothing is announced. */
    @Autowired(required = false)
    void setEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    public Status status() {
        boolean configured = reader.isConfigured();
        int minutes = settings.intervalMinutes();
        Instant next = configured && lastSync != null ? lastSync.plus(Duration.ofMinutes(minutes)) : null;
        return new Status(configured, folders, repository.pendingCount(), lastSync, lastError, syncing.get(), lastResult, minutes, next);
    }

    public int intervalMinutes() {
        return settings.intervalMinutes();
    }

    /** Sets how often the mailbox is read; only the steps in {@link MailSettings#ALLOWED_MINUTES} are accepted. */
    public int setIntervalMinutes(int minutes) {
        return settings.setIntervalMinutes(minutes);
    }

    /**
     * Starts a read of the mailbox in the background and answers at once: reading a large folder can take minutes,
     * far longer than a request should be held. The screen then polls {@link #status()} until syncing is false and
     * shows lastResult.
     */
    public SyncStart startSync() {
        return start(false);
    }

    /**
     * Like {@link #startSync()}, but first forgets which messages the mail tracker has handled, so the read offers
     * every message of the window again. Stored mail is kept (a digest stored earlier gets its postings; a mail the
     * owner deleted comes back); it is never duplicated, because a mail is stored once per Message-ID. The forgetting
     * happens inside the background run, after the one-at-a-time flag is held, so a read that is already running is
     * never disturbed.
     */
    public SyncStart startReread() {
        return start(true);
    }

    private SyncStart start(boolean reread) {
        if (!reader.isConfigured()) {
            return new SyncStart(false, "The mailbox is not set up");
        }
        if (!syncing.compareAndSet(false, true)) {
            return new SyncStart(false, "A read is already running");
        }
        try {
            syncExecutor.submit(() -> {
                try {
                    if (!reread || forgetHandled()) {
                        syncHoldingFlag();
                    }
                } finally {
                    syncing.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            syncing.set(false);
            return new SyncStart(false, "The application is shutting down");
        }
        return new SyncStart(true, null);
    }

    @PreDestroy
    void shutdown() {
        syncExecutor.shutdownNow();
    }

    /** Ticks every minute and reads the mailbox when the chosen interval has passed since the last read (or none ran yet). */
    @Scheduled(initialDelayString = "${app.mail.replies.initial-delay:PT1M}", fixedDelayString = "${app.mail.replies.tick:PT1M}")
    void scheduledSync() {
        if (!reader.isConfigured()) {
            return;
        }
        try {
            if (MailSettings.due(lastSync, settings.interval(), clock.instant())) {
                sync();
            }
        } catch (DataAccessException e) {
            LOG.warn("Mail: could not read the interval setting: {}", e.toString());
        }
    }

    /** Reads every configured folder now. Never throws for a mailbox problem: the result carries the error. */
    public SyncResult sync() {
        if (!reader.isConfigured()) {
            return new SyncResult(false, 0, 0, 0, 0, null);
        }
        if (!syncing.compareAndSet(false, true)) {
            return new SyncResult(true, 0, 0, 0, 0, "A read is already running");
        }
        try {
            return syncHoldingFlag();
        } finally {
            syncing.set(false);
        }
    }

    /** Forgets the mail tracker's handled marks; when the database refuses, the result says so and no read follows. */
    private boolean forgetHandled() {
        try {
            LOG.info("Mail: re-reading the window, {} handled marks forgotten", reader.forgetProcessed(KEY_PREFIX));
            return true;
        } catch (DataAccessException e) {
            LOG.warn("Mail: could not forget the handled marks: {}", e.toString());
            lastError = "Could not start the re-read: the database did not answer.";
            lastResult = new SyncResult(true, 0, 0, 0, 0, lastError);
            return false;
        }
    }

    /** The caller has set the syncing flag; the lock keeps the read single-threaded. */
    private synchronized SyncResult syncHoldingFlag() {
        return readFolders();
    }

    private SyncResult readFolders() {
        int seen = 0;
        int stored = 0;
        int linked = 0;
        int suggested = 0;
        List<MailSuggestionsEvent.Item> pending = new ArrayList<>();
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
                    if ("pending".equals(o.correspondence().suggestionState())) {
                        suggested++;
                        pending.add(new MailSuggestionsEvent.Item(o.correspondence().category(), o.correspondence().suggestedStatus(),
                                o.correspondence().vacancyId(), o.correspondence().subject()));
                    }
                }
            }
        }
        lastSync = clock.instant();
        lastError = error;
        if (stored > 0) {
            LOG.info("Mail: {} job mails stored, {} linked to applications, {} status suggestions", stored, linked, suggested);
        }
        SyncResult result = new SyncResult(true, seen, stored, linked, suggested, error);
        lastResult = result;
        announce(pending);
        return result;
    }

    /** One event per read, only for mails this read stored; the listener hands off to its own thread and never throws into the read. */
    private void announce(List<MailSuggestionsEvent.Item> pending) {
        if (events != null && !pending.isEmpty()) {
            events.publishEvent(new MailSuggestionsEvent(pending));
        }
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
            if (known.get().event() == null) {
                // A mail stored before dates were read gets its interview time now, like a digest gets its postings.
                eventOf(mail, category).ifPresent(e -> repository.updateEvent(known.get().id(), e));
            }
            if (known.get().jobs().isEmpty()) {
                // A digest stored before its postings were read (or imported again by hand) gets them now.
                List<Correspondence.DigestJob> jobs = digestJobs(mail);
                if (!jobs.isEmpty()) {
                    repository.updateJobs(known.get().id(), jobs);
                }
            }
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
                clock.instant(), digestJobs(mail), eventOf(mail, category).orElse(null));
        repository.insert(c);
        return new Outcome(true, category.code(), c);
    }

    /**
     * The date and time an interview invitation or a test task names, read from the whole mail (not only the stored
     * text). Other mail has no event. The day the mail came is the reference for "tomorrow" and for past dates.
     */
    Optional<Correspondence.Event> eventOf(MailMessage mail, MailCategory category) {
        if (category != MailCategory.INTERVIEW && category != MailCategory.TEST_TASK) {
            return Optional.empty();
        }
        String text = (mail.subject() == null ? "" : mail.subject()) + "\n" + MailClassifier.body(mail);
        Instant reference = mail.receivedAt() == null ? clock.instant() : mail.receivedAt();
        return MailEventExtractor.extract(text, reference)
                .map(f -> new Correspondence.Event(f.start(), f.zoneKnown(), f.text()));
    }

    /** Most postings kept from one digest: a longer list is cut, the mail itself is still there. */
    static final int MAX_DIGEST_JOBS = 30;

    /**
     * The postings of a digest mail: a mail from a job site whose parser finds two or more. One posting is not a digest
     * (a single-vacancy notice, for example "your application was sent", says more as plain text). A layout the parser
     * does not know gives an empty list; the parser never fails the mail.
     */
    List<Correspondence.DigestJob> digestJobs(MailMessage mail) {
        try {
            List<ParsedJob> parsed = parsers.forMessage(mail).map(p -> p.parse(mail)).orElse(List.of());
            if (parsed.size() < 2) {
                return List.of();
            }
            return parsed.stream().limit(MAX_DIGEST_JOBS)
                    .map(j -> new Correspondence.DigestJob(j.title(), j.company(), j.location(), j.url())).toList();
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            LOG.warn("Mail: the postings of a digest could not be read: {}", e.toString());
            return List.of();
        }
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
        return withVacancies(repository.list(vacancyId, pendingOnly, 200));
    }

    /** Marks each posting of the digests with the vacancy it already is: one query for the links of all the mails. */
    private List<Correspondence> withVacancies(List<Correspondence> mails) {
        List<String> urls = mails.stream().flatMap(m -> m.jobs().stream()).map(Correspondence.DigestJob::url)
                .filter(u -> u != null).distinct().toList();
        if (urls.isEmpty()) {
            return mails;
        }
        Map<String, UUID> known = vacancies.findIdsByUrls(urls);
        if (known.isEmpty()) {
            return mails;
        }
        return mails.stream().map(m -> m.jobs().isEmpty() ? m : m.withJobs(m.jobs().stream()
                .map(j -> j.url() == null || !known.containsKey(j.url()) ? j
                        : new Correspondence.DigestJob(j.title(), j.company(), j.location(), j.url(), known.get(j.url())))
                .toList())).toList();
    }

    /**
     * Adds one posting of a digest mail to the Pipeline as a New vacancy. Idempotent: a posting whose link is already a
     * vacancy (added before, or found by an alert source) gives that vacancy back. The link is only compared, never
     * fetched; anything but an http(s) address is refused.
     */
    public AddedJob addToPipeline(UUID mailId, int index) {
        Correspondence c = get(mailId);
        if (index < 0 || index >= c.jobs().size()) {
            throw new NotFoundException("This mail has no posting number " + index);
        }
        Correspondence.DigestJob job = c.jobs().get(index);
        String link = CanonicalUrl.webLink(job.url());
        if (link == null) {
            throw new IllegalArgumentException("This posting has no web link to add it by.");
        }
        Optional<UUID> existing = vacancies.findIdByUrl(link);
        if (existing.isPresent()) {
            return new AddedJob(existing.get(), false);
        }
        try {
            Vacancy created = vacancies.createImported(link, new ImportedPosting(job.title(), job.company(), job.location(),
                    null, null, null, null, null, null));
            return new AddedJob(created.getId(), true);
        } catch (DuplicateKeyException e) {
            // A second click (or an alert source) stored the same link a moment ago: that vacancy is the answer.
            return new AddedJob(vacancies.findIdByUrl(link).orElseThrow(() -> e), false);
        }
    }

    /** The vacancy a posting is in the Pipeline; created is false when it was there already. */
    public record AddedJob(UUID vacancyId, boolean created) {
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
        return repository.deleteBefore(clock.instant().minus(Duration.ofDays(days)));
    }

    public Correspondence get(UUID id) {
        Correspondence c = repository.find(id).orElseThrow(() -> new NotFoundException("Mail not found: " + id));
        return withVacancies(List.of(c)).get(0);
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
