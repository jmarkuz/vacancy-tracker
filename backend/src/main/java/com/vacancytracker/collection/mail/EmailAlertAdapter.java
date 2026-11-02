package com.vacancytracker.collection.mail;

import com.vacancytracker.collection.adapter.FetchOutcome;
import com.vacancytracker.collection.adapter.RawPosting;
import com.vacancytracker.collection.adapter.SourceAdapter;
import com.vacancytracker.collection.http.UpstreamException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A source that reads alert emails of one sender from the IMAP folder (T-065). Each run reads the unprocessed
 * messages: those of this sender are parsed and marked processed; those of a sender nobody handles are logged
 * and marked processed so they cannot pile up; those of another known sender are left for that sender's adapter.
 * Without IMAP settings it does nothing. The vacancies carry the email's date as their posting date.
 */
public abstract class EmailAlertAdapter implements SourceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(EmailAlertAdapter.class);

    private final MailParser parser;
    private final MailParsers parsers;
    private final ImapMailReader reader;

    protected EmailAlertAdapter(MailParser parser, MailParsers parsers, ImapMailReader reader) {
        this.parser = parser;
        this.parsers = parsers;
        this.reader = reader;
    }

    @Override
    public String id() {
        return parser.adapterId();
    }

    @Override
    public Set<String> serverSideFields() {
        return Set.of();
    }

    @Override
    public FetchOutcome fetch() {
        if (!reader.isConfigured()) {
            return FetchOutcome.of(List.of());
        }
        List<RawPosting> postings = new ArrayList<>();
        ImapMailReader.ReadResult result = reader.readNewDeferred(mail -> handle(mail, postings));
        if (result.error() != null) {
            throw new UpstreamException(result.error());
        }
        // The mails are marked processed only after the pipeline has stored their vacancies.
        return FetchOutcome.of(postings).afterStored(() -> reader.markProcessed(result.handledKeys()));
    }

    private MailHandler.Outcome handle(MailMessage mail, List<RawPosting> postings) {
        if (parser.accepts(mail)) {
            List<ParsedJob> jobs = parser.parse(mail);
            if (jobs.isEmpty()) {
                LOG.warn("{}: an email from {} had no recognisable vacancies; its layout may have changed", id(), mail.from());
            }
            for (ParsedJob job : jobs) {
                postings.add(new RawPosting(id(), job.externalId(), job.url(), job.title(), job.company(), job.location(),
                        null, null, null, null, null, null, mail.receivedAt()));
            }
            return MailHandler.Outcome.PROCESSED;
        }
        if (parsers.forMessage(mail).isPresent()) {
            return MailHandler.Outcome.RETRY;
        }
        LOG.info("{}: skipped an email from {} with no parser for that sender", id(), mail.from());
        return MailHandler.Outcome.PROCESSED;
    }
}
