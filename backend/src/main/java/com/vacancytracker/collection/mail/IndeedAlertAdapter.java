package com.vacancytracker.collection.mail;

import org.springframework.stereotype.Component;

/** Indeed job alert emails as a source (T-066). */
@Component
public class IndeedAlertAdapter extends EmailAlertAdapter {

    public IndeedAlertAdapter(IndeedAlertParser parser, MailParsers parsers, ImapMailReader reader) {
        super(parser, parsers, reader);
    }
}
