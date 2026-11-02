package com.vacancytracker.collection.mail;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Indeed job alert emails: links to a country site of indeed.com with the job key in the jk parameter. The plain
 * link keeps the country host of the email. Written from the public link format, not from a real email.
 */
@Component
public class IndeedAlertParser extends LinkAlertParser {

    public static final String ID = "email-indeed";

    public IndeedAlertParser() {
        super(ID, "indeed.com",
                Pattern.compile("indeed\\.com/(?:rc/clk|viewjob|pagead/clk)[^\\s\"'<>]*?[?&;]jk=([0-9a-f]{8,})"),
                (id, host) -> "https://" + (host == null || host.isBlank() ? "www.indeed.com" : host) + "/viewjob?jk=" + id,
                List.of("view job", "apply now", "apply", "see all jobs", "view all jobs", "unsubscribe", "easily apply"));
    }
}
