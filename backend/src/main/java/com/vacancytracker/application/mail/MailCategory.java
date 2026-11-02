package com.vacancytracker.application.mail;

import com.vacancytracker.vacancy.VacancyStatus;

/**
 * What a job-related email says (T-086, ADR-043), and the status it points to when it is about an application.
 * Order matters for classification: the first category whose phrases match wins (see {@link MailClassifier}).
 */
public enum MailCategory {
    REJECTION("rejection", VacancyStatus.REJECTED),
    OFFER("offer", VacancyStatus.OFFER),
    TEST_TASK("test_task", VacancyStatus.TEST_TASK),
    INTERVIEW("interview", VacancyStatus.INTERVIEW),
    APPLICATION_VIEWED("application_viewed", null),
    APPLICATION_RECEIVED("application_received", VacancyStatus.APPLIED),
    RECRUITER_MESSAGE("recruiter_message", null),
    JOB_ALERT("job_alert", null),
    OTHER_JOB("other_job", null),
    NOT_JOB("not_job", null);

    private final String code;
    private final VacancyStatus suggests;

    MailCategory(String code, VacancyStatus suggests) {
        this.code = code;
        this.suggests = suggests;
    }

    public String code() {
        return code;
    }

    /** The status this kind of mail moves an application to, or null when it does not move it. */
    public VacancyStatus suggests() {
        return suggests;
    }

    public static MailCategory of(String code) {
        for (MailCategory c : values()) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown mail category: " + code);
    }
}
