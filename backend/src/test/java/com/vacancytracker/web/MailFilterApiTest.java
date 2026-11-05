package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /api/mail filters (category, linked, q) and GET /api/mail/counts. */
@SpringBootTest
@AutoConfigureMockMvc
class MailFilterApiTest extends AbstractIntegrationTest {

    private static final String JOB = "{\"title\":\"%s\",\"company\":null,\"location\":null,\"url\":null}";
    private static final String TWO_JOBS = "[" + JOB.formatted("A") + "," + JOB.formatted("B") + "]";
    private static final String ONE_JOB = "[" + JOB.formatted("A") + "]";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID vacancy;

    @BeforeEach
    void rows() {
        vacancy = UUID.randomUUID();
        jdbc.update("INSERT INTO vacancy (id, title, company, status, first_seen_at, last_seen_at) VALUES (?, 'Dev', 'Acme', 'Applied', now(), now())", vacancy);
        mail("digest", "Jobs for you", "jobs@board.example", "Board", "Roles: Java", "job_alert", null, "pending", TWO_JOBS, 9);
        mail("int", "Interview invitation", "hr@acme.example", "Anna Recruiter", "Please pick a slot", "interview", vacancy, "pending", null, 8);
        mail("off", "Your offer", "hr@beta.example", "Beta HR", "We offer 100% remote", "offer", null, "none", null, 7);
        mail("rej", "Update on your application", "hr@acme.example", "Acme HR", "Unfortunately no", "rejection", vacancy, "none", null, 6);
        mail("test", "Test task", "tests@gamma.example", "Gamma", "Do the task_one", "test_task", null, "none", null, 5);
        mail("rec", "Hello from a recruiter", "rec@delta.example", null, "Open to talk?", "recruiter_message", null, "none", null, 4);
        mail("oth", "Thanks for applying", "noreply@eps.example", "Epsilon", "We got it", "other_job", null, "none", ONE_JOB, 3);
        mail("view", "Viewed", "noreply@zeta.example", "Zeta", "Viewed your application", "application_viewed", vacancy, "none", null, 2);
        // A digest the classifier called an interview: it is a job alert, not an interview.
        mail("dig2", "Interview roles weekly", "alerts@eta.example", "Eta", "More roles", "interview", null, "none", TWO_JOBS, 1);
    }

    private void mail(String key, String subject, String sender, String name, String snippet, String category, UUID linked, String state,
            String jobs, int daysAgo) {
        jdbc.update("INSERT INTO correspondence (id, mail_key, sender, sender_name, subject, snippet, category, vacancy_id, suggestion_state,"
                        + " created_at, received_at, jobs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now() - make_interval(days => ?), CAST(? AS jsonb))",
                UUID.randomUUID(), key, sender, name, subject, snippet, category, linked, state, daysAgo, jobs);
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get(java.net.URI.create("/api/mail" + query))).andExpect(status().isOk());
    }

    @Test
    void withoutFiltersEveryMailComesNewestFirst() throws Exception {
        list("").andExpect(jsonPath("$.length()").value(9)).andExpect(jsonPath("$[0].mailKey").value("dig2"));
    }

    @Test
    void jobAlertsAreTheMailsWithTwoOrMorePostingsWhateverTheirCategory() throws Exception {
        list("?category=job_alert").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mailKey").value("dig2")).andExpect(jsonPath("$[1].mailKey").value("digest"));
    }

    @Test
    void aDigestIsNotCountedAsItsOwnCategoryAndASinglePostingIsAnOrdinaryMail() throws Exception {
        list("?category=interview").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].mailKey").value("int"));
        list("?category=other").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void eachNamedCategoryFindsItsMail() throws Exception {
        list("?category=offer").andExpect(jsonPath("$[0].mailKey").value("off")).andExpect(jsonPath("$.length()").value(1));
        list("?category=rejection").andExpect(jsonPath("$[0].mailKey").value("rej")).andExpect(jsonPath("$.length()").value(1));
        list("?category=test_task").andExpect(jsonPath("$[0].mailKey").value("test")).andExpect(jsonPath("$.length()").value(1));
        list("?category=recruiter_message").andExpect(jsonPath("$[0].mailKey").value("rec")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void severalCategoriesCanBeRepeatedOrCommaSeparated() throws Exception {
        list("?category=offer&category=rejection").andExpect(jsonPath("$.length()").value(2));
        list("?category=offer,%20rejection,").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void linkedAndNotLinkedSplitTheList() throws Exception {
        list("?linked=true").andExpect(jsonPath("$.length()").value(3));
        list("?linked=false").andExpect(jsonPath("$.length()").value(6));
        list("?linked=true&category=interview").andExpect(jsonPath("$.length()").value(1));
        list("?linked=false&category=interview").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void textSearchLooksAtSubjectSenderNameSenderAddressAndStoredTextIgnoringCase() throws Exception {
        list("?q=INVITATION").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].mailKey").value("int"));
        list("?q=anna").andExpect(jsonPath("$[0].mailKey").value("int"));
        list("?q=beta.example").andExpect(jsonPath("$[0].mailKey").value("off"));
        list("?q=pick%20a%20slot").andExpect(jsonPath("$[0].mailKey").value("int"));
        list("?q=nothing-like-this").andExpect(jsonPath("$.length()").value(0));
        list("?q=%20%20").andExpect(jsonPath("$.length()").value(9));
    }

    @Test
    void percentAndUnderscoreAndBackslashMatchOnlyThemselves() throws Exception {
        list("?q=100%25").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].mailKey").value("off"));
        list("?q=%25").andExpect(jsonPath("$.length()").value(1));
        list("?q=task_one").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].mailKey").value("test"));
        list("?q=task%2Bone").andExpect(jsonPath("$.length()").value(0));
        list("?q=_").andExpect(jsonPath("$.length()").value(1));
        list("?q=%5C").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aQuoteInTheTextIsJustText() throws Exception {
        list("?q=%27%3B%20DROP%20TABLE%20correspondence%3B%20--").andExpect(jsonPath("$.length()").value(0));
        list("").andExpect(jsonPath("$.length()").value(9));
    }

    @Test
    void filtersCombineWithEachOtherAndWithVacancyIdAndPending() throws Exception {
        list("?category=interview&linked=true&q=acme").andExpect(jsonPath("$.length()").value(1));
        list("?category=rejection&q=unfortunately&linked=true").andExpect(jsonPath("$.length()").value(1));
        list("?category=rejection&q=slot").andExpect(jsonPath("$.length()").value(0));
        list("?vacancyId=" + vacancy + "&category=interview,rejection").andExpect(jsonPath("$.length()").value(2));
        list("?vacancyId=" + vacancy).andExpect(jsonPath("$.length()").value(3));
        list("?pending=true").andExpect(jsonPath("$.length()").value(2));
        list("?pending=true&category=job_alert").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].mailKey").value("digest"));
    }

    @Test
    void anUnknownCategoryOrAnOverlongTextIsA400() throws Exception {
        mvc.perform(get("/api/mail?category=nope")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/mail?category=offer,nope")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/mail/counts?category=not_job")).andExpect(status().isBadRequest());
        mvc.perform(get(java.net.URI.create("/api/mail?q=" + "x".repeat(201)))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/mail?linked=maybe")).andExpect(status().isBadRequest());
    }

    @Test
    void countsSplitTheWholeSetByCategoryAndAddUp() throws Exception {
        mvc.perform(get("/api/mail/counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.categories.job_alert").value(2))
                .andExpect(jsonPath("$.categories.interview").value(1))
                .andExpect(jsonPath("$.categories.offer").value(1))
                .andExpect(jsonPath("$.categories.rejection").value(1))
                .andExpect(jsonPath("$.categories.test_task").value(1))
                .andExpect(jsonPath("$.categories.recruiter_message").value(1))
                .andExpect(jsonPath("$.categories.other").value(2));
    }

    @Test
    void countsFollowTheOtherFiltersButIgnoreTheChosenCategories() throws Exception {
        mvc.perform(get("/api/mail/counts?linked=true&category=offer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.categories.interview").value(1))
                .andExpect(jsonPath("$.categories.rejection").value(1))
                .andExpect(jsonPath("$.categories.other").value(1))
                .andExpect(jsonPath("$.categories.offer").value(0))
                .andExpect(jsonPath("$.categories.job_alert").value(0));
        mvc.perform(get("/api/mail/counts?q=acme&pending=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.categories.interview").value(1));
    }

    @Test
    void countsOfAnEmptyStoreAreZero() throws Exception {
        jdbc.update("DELETE FROM correspondence");
        mvc.perform(get("/api/mail/counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.categories.other").value(0));
    }
}
