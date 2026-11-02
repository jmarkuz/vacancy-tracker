package com.vacancytracker.collection.mail;

import org.springframework.stereotype.Component;

/** LinkedIn job alert emails as a source (T-065). */
@Component
public class LinkedInAlertAdapter extends EmailAlertAdapter {

    public LinkedInAlertAdapter(LinkedInAlertParser parser, MailParsers parsers, ImapMailReader reader) {
        super(parser, parsers, reader);
    }
}
