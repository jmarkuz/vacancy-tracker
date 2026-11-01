package com.vacancytracker.collection.mail;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Jobs.cz job alert emails: links to jobs.cz/rpd/{id}. Written from the public link format, not from a real email. */
@Component
public class JobsCzAlertParser extends LinkAlertParser {

    public static final String ID = "email-jobscz";

    public JobsCzAlertParser() {
        super(ID, "jobs.cz",
                Pattern.compile("jobs\\.cz/(?:rpd|prace)/(\\d{5,})"),
                (id, host) -> "https://www.jobs.cz/rpd/" + id + "/",
                List.of("zobrazit nabídku", "zobrazit", "více", "zobrazit všechny nabídky", "odhlásit", "view job", "apply"));
    }
}
