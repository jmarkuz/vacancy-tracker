package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.mail.MailMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MailClassifierTest {

    private static MailMessage mail(String from, String subject, String text) {
        return new MailMessage("<k@x>", from, null, subject, Instant.parse("2026-11-02T10:00:00Z"), text, null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Your application to Acme | Thank you for applying. Unfortunately we decided to move forward with other candidates. | rejection",
        "Re: Backend Engineer | На жаль, ми не можемо запропонувати вам цю позицію. | rejection",
        "Offer | We are pleased to offer you the position of Backend Engineer. | offer",
        "Next steps | Please complete the take-home assignment within 5 days. | test_task",
        "Тестове завдання | Надсилаємо тестове завдання для позиції Java Developer. | test_task",
        "Interview invitation | We would like to invite you to an interview. Book a time on calendly.com/acme. | interview",
        "Запрошення | Запрошуємо вас на співбесіду у вівторок. | interview",
        "We received your application | Thank you for your application to the Backend Engineer role. | application_received",
        "Дякуємо | Дякуємо за ваш відгук на вакансію. | application_received",
        "Quarterly newsletter | Our product news for this month. | not_job",
        "Lunch? | Shall we meet on Friday? | not_job"})
    void sortsMailByItsWords(String subject, String text, String category) {
        assertThat(MailClassifier.classify(mail("hr@acme.example", subject, text)).code()).isEqualTo(category);
    }

    @Test
    void tellsLinkedInNoticesApart() {
        assertThat(MailClassifier.classify(mail("jobs-noreply@linkedin.com", "Your application was sent to Acme",
                "Your application was sent to Acme. Backend Engineer."))).isEqualTo(MailCategory.APPLICATION_RECEIVED);
        assertThat(MailClassifier.classify(mail("jobs-noreply@linkedin.com", "Your application was viewed by Acme",
                "Acme viewed your application for Backend Engineer."))).isEqualTo(MailCategory.APPLICATION_VIEWED);
        assertThat(MailClassifier.classify(mail("messages-noreply@linkedin.com", "Jane sent you a message",
                "Jane Recruiter sent you a new message about a role."))).isEqualTo(MailCategory.RECRUITER_MESSAGE);
        assertThat(MailClassifier.classify(mail("jobalerts-noreply@linkedin.com", "5 new jobs for you: Java Developer",
                "Prepare for your interview with these jobs."))).isEqualTo(MailCategory.JOB_ALERT);
        assertThat(MailClassifier.classify(mail("hr@acme.example", "Hello", "About the position and your resume")))
                .isEqualTo(MailCategory.OTHER_JOB);
    }

    @Test
    void aHugeMailIsClassifiedQuicklyOnItsFirstPart() {
        String text = "recruiter " + "x ".repeat(2_000_000) + " position";
        long started = System.nanoTime();

        MailCategory category = MailClassifier.classify(mail("a@b.example", "Hello", text));

        assertThat(category).isEqualTo(MailCategory.NOT_JOB);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(MailClassifier.body(mail("a@b.example", "s", text))).hasSize(MailClassifier.MAX_TEXT);
    }

    @Test
    void snippetsAreShortPlainText() {
        MailMessage html = new MailMessage("<k@x>", "a@b.c", null, "s", null, null, "<p>Hello</p><p>" + "x".repeat(700) + "</p>");

        assertThat(MailClassifier.snippet(html, 20)).startsWith("Hello").endsWith("…").hasSize(21);
    }

    @Test
    void linksByCompanyDomainAndTitleWithTheReason() {
        UUID acme = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        List<MailLinker.Candidate> candidates = List.of(
                new MailLinker.Candidate(acme, "Backend Engineer", "Acme Pay, Inc.", "https://boards.greenhouse.io/acmepay/jobs/1", true),
                new MailLinker.Candidate(beta, "Data Analyst", "Beta", "https://beta.example/jobs/2", false));

        Optional<MailLinker.Link> link = MailLinker.link(new MailMessage("<k@x>", "talent@acmepay.com", "Acme Pay Recruiting",
                "Interview: Backend Engineer", null, "Hi Jane", null), candidates);

        assertThat(link).isPresent();
        assertThat(link.get().vacancyId()).isEqualTo(acme);
        assertThat(link.get().reason()).isEqualTo("The company name is in the sender's name; the sender's domain matches the company "
                + "or its posting; the vacancy title is in the mail; you applied.");
        assertThat(MailLinker.link(mail("friend@gmail.com", "Hi", "Nothing about work"), candidates)).isEmpty();
        assertThat(MailLinker.companyKey("Acme Pay, Inc.")).isEqualTo("acme pay");
        assertThat(MailLinker.companyKey("ТОВ «Бета Софт»")).isEqualTo("бета софт");
    }
}
