package com.vacancytracker.collection.mail;

import org.springframework.stereotype.Component;

/** JobsCz job alert emails as a source (T-066). */
@Component
public class JobsCzAlertAdapter extends EmailAlertAdapter {

    public JobsCzAlertAdapter(JobsCzAlertParser parser, MailParsers parsers, ImapMailReader reader) {
        super(parser, parsers, reader);
    }
}
