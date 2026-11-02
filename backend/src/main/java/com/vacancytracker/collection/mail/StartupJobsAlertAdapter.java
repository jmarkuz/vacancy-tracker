package com.vacancytracker.collection.mail;

import org.springframework.stereotype.Component;

/** StartupJobs job alert emails as a source (T-066). */
@Component
public class StartupJobsAlertAdapter extends EmailAlertAdapter {

    public StartupJobsAlertAdapter(StartupJobsAlertParser parser, MailParsers parsers, ImapMailReader reader) {
        super(parser, parsers, reader);
    }
}
