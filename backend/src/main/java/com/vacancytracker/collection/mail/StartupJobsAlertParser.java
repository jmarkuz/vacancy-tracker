package com.vacancytracker.collection.mail;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** StartupJobs.cz job alert emails: links to startupjobs.cz/nabidka/{id}. Written from the public link format. */
@Component
public class StartupJobsAlertParser extends LinkAlertParser {

    public static final String ID = "email-startupjobs";

    public StartupJobsAlertParser() {
        super(ID, "startupjobs.cz",
                Pattern.compile("startupjobs\\.cz/(?:nabidka|offer)/(\\d{3,})"),
                (id, host) -> "https://www.startupjobs.cz/nabidka/" + id,
                List.of("zobrazit nabídku", "zobrazit", "více", "všechny nabídky", "odhlásit", "view job", "apply"));
    }
}
