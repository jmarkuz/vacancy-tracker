package com.vacancytracker.collection.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One fixture per sender (T-066). The fixtures are synthetic: see the comment at the top of each file. */
class SenderParsersTest {

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/mail", name), StandardCharsets.UTF_8);
    }

    private static MailMessage mail(String from, String text, String html) {
        return new MailMessage("<k@x>", from, "alert", Instant.parse("2026-11-01T10:00:00Z"), text, html);
    }

    @Test
    void jobsCzHtmlGivesBothVacanciesWithPlainLinks() throws IOException {
        List<ParsedJob> jobs = new JobsCzAlertParser().parse(mail("noreply@jobs.cz", null, fixture("jobscz-alert.html")));

        assertThat(jobs).containsExactly(
                new ParsedJob("2000111222", "https://www.jobs.cz/rpd/2000111222/", "Example Role Alpha", "Northwind Labs s.r.o.", "Praha"),
                new ParsedJob("2000333444", "https://www.jobs.cz/rpd/2000333444/", "Senior Example Role", "Vantage Lane a.s.", "Brno"));
    }

    @Test
    void jobsCzTextFallback() throws IOException {
        List<ParsedJob> jobs = new JobsCzAlertParser().parse(mail("noreply@jobs.cz", fixture("jobscz-alert.txt"), null));

        assertThat(jobs).extracting(ParsedJob::externalId).containsExactly("2000111222", "2000333444");
        assertThat(jobs.get(1).company()).isEqualTo("Vantage Lane a.s.");
    }

    @Test
    void startupJobsSplitsCompanyAndPlaceFromOneLine() throws IOException {
        List<ParsedJob> jobs = new StartupJobsAlertParser().parse(mail("hello@startupjobs.cz", null, fixture("startupjobs-alert.html")));

        assertThat(jobs).containsExactly(
                new ParsedJob("12345", "https://www.startupjobs.cz/nabidka/12345", "Example Role Alpha", "Northwind Labs", "Praha / Remote"),
                new ParsedJob("67890", "https://www.startupjobs.cz/nabidka/67890", "Senior Example Role", "Vantage Lane", "Brno"));
    }

    @Test
    void startupJobsTextFallback() throws IOException {
        List<ParsedJob> jobs = new StartupJobsAlertParser().parse(mail("hello@startupjobs.cz", fixture("startupjobs-alert.txt"), null));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).title()).isEqualTo("Example Role Alpha");
    }

    @Test
    void indeedReadsTheJobKeyAndKeepsTheCountrySite() throws IOException {
        List<ParsedJob> jobs = new IndeedAlertParser().parse(mail("alert@indeed.com", null, fixture("indeed-alert.html")));

        assertThat(jobs).containsExactly(
                new ParsedJob("0123456789abcdef", "https://cz.indeed.com/viewjob?jk=0123456789abcdef", "Example Role Alpha", "Northwind Labs", "Praha"),
                new ParsedJob("fedcba9876543210", "https://cz.indeed.com/viewjob?jk=fedcba9876543210", "Senior Example Role", "Vantage Lane", "Brno"));
    }

    @Test
    void indeedTextFallback() throws IOException {
        List<ParsedJob> jobs = new IndeedAlertParser().parse(mail("alert@indeed.com", fixture("indeed-alert.txt"), null));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).url()).isEqualTo("https://cz.indeed.com/viewjob?jk=0123456789abcdef");
    }

    @Test
    void buttonsAndFooterLinksAreNeverVacancies() throws IOException {
        assertThat(new JobsCzAlertParser().parse(mail("a@jobs.cz", null, fixture("jobscz-alert.html"))))
                .extracting(ParsedJob::title).doesNotContain("Zobrazit nabídku", "Odhlásit");
        assertThat(new IndeedAlertParser().parse(mail("a@indeed.com", null, fixture("indeed-alert.html"))))
                .extracting(ParsedJob::title).doesNotContain("Apply now", "See all jobs");
        assertThat(new StartupJobsAlertParser().parse(mail("a@startupjobs.cz", null, fixture("startupjobs-alert.html"))))
                .extracting(ParsedJob::title).doesNotContain("Zobrazit nabídku", "Všechny nabídky");
    }

    @Test
    void eachParserOnlyAcceptsItsOwnDomainAndNeverAnotherSendersLinks() throws IOException {
        assertThat(new JobsCzAlertParser().accepts(mail("a@jobs.cz", null, null))).isTrue();
        assertThat(new JobsCzAlertParser().accepts(mail("a@startupjobs.cz", null, null))).isFalse();
        assertThat(new StartupJobsAlertParser().accepts(mail("a@mail.startupjobs.cz", null, null))).isTrue();
        assertThat(new StartupJobsAlertParser().accepts(mail("a@notstartupjobs.cz", null, null))).isFalse();
        assertThat(new IndeedAlertParser().accepts(mail("a@alert.indeed.com", null, null))).isTrue();
        assertThat(new IndeedAlertParser().accepts(mail("a@indeed.com.evil.test", null, null))).isFalse();
        assertThat(new IndeedAlertParser().parse(mail("a@indeed.com", null, fixture("jobscz-alert.html")))).isEmpty();
        assertThat(new JobsCzAlertParser().parse(mail("a@jobs.cz", null, fixture("linkedin-alert.html")))).isEmpty();
    }

    @Test
    void aChangedTemplateGivesNothingInsteadOfAnError() {
        for (LinkAlertParser parser : List.of(new JobsCzAlertParser(), new StartupJobsAlertParser(), new IndeedAlertParser())) {
            assertThat(parser.parse(mail("a@x", "Hello", "<p>Redesigned, no links</p>"))).isEmpty();
            assertThat(parser.parse(mail("a@x", null, null))).isEmpty();
        }
    }
}
