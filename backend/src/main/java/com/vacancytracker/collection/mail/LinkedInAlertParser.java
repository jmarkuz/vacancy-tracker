package com.vacancytracker.collection.mail;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** LinkedIn job alert emails: links to linkedin.com/jobs/view/{id}. */
@Component
public class LinkedInAlertParser extends LinkAlertParser {

    public static final String ID = "email-linkedin";

    public LinkedInAlertParser() {
        super(ID, "linkedin.com",
                Pattern.compile("linkedin\\.com/(?:comm/)?jobs/view/(?:[^/?#]*?-)?(\\d{6,})"),
                (id, host) -> "https://www.linkedin.com/jobs/view/" + id + "/",
                List.of("view job", "view jobs", "apply", "see all jobs", "view all jobs", "see more jobs"));
    }
}
