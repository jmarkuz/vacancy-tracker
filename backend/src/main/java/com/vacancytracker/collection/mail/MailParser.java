package com.vacancytracker.collection.mail;

import java.util.List;

/**
 * Reads the vacancies out of alert emails from one sender (T-065). One parser per sender. A parser only looks at
 * the email content: it never opens a link, never logs in to the sender's site and never fetches anything.
 */
public interface MailParser {

    /** The source id stored with each vacancy, for example email-linkedin. */
    String adapterId();

    /** Whether this parser is for the sender of the message. */
    boolean accepts(MailMessage mail);

    /** The vacancies in the message. Empty when the email has none or its layout is not recognised. */
    List<ParsedJob> parse(MailMessage mail);

    /** True when the address belongs to the domain or one of its subdomains. */
    static boolean fromDomain(String address, String domain) {
        if (address == null) {
            return false;
        }
        int at = address.lastIndexOf('@');
        String host = (at < 0 ? address : address.substring(at + 1)).trim().toLowerCase(java.util.Locale.ROOT);
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
