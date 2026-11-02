package com.vacancytracker.collection.mail;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** All known email parsers, and the question "does anyone understand mail from this sender". */
@Component
public class MailParsers {

    private final List<MailParser> parsers;

    public MailParsers(List<MailParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public Optional<MailParser> forMessage(MailMessage mail) {
        return parsers.stream().filter(p -> p.accepts(mail)).findFirst();
    }
}
