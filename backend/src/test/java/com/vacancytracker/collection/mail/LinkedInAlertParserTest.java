package com.vacancytracker.collection.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinkedInAlertParserTest {

    private final LinkedInAlertParser parser = new LinkedInAlertParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/mail", name), StandardCharsets.UTF_8);
    }

    private static MailMessage mail(String from, String text, String html) {
        return new MailMessage("<k@x>", from, "subject", Instant.parse("2026-11-01T10:00:00Z"), text, html);
    }

    @Test
    void readsEachVacancyOnceWithTitleCompanyAndPlaceFromTheHtml() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("linkedin-alert.html")));

        assertThat(jobs).containsExactly(
                new ParsedJob("3912345678", "https://www.linkedin.com/jobs/view/3912345678/", "Example Role Alpha", "Northwind Labs", "Prague, Czechia"),
                new ParsedJob("3987654321", "https://www.linkedin.com/jobs/view/3987654321/", "Senior Example Role", "Vantage Lane", "Remote"));
    }

    @Test
    void linksAreRewrittenWithoutTrackingParameters() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("linkedin-alert.html")));

        assertThat(jobs).allSatisfy(j -> assertThat(j.url()).matches("https://www\\.linkedin\\.com/jobs/view/\\d+/"));
    }

    @Test
    void theViewJobButtonAndFooterLinksAreNotVacancies() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("linkedin-alert.html")));

        assertThat(jobs).extracting(ParsedJob::title).doesNotContain("View job", "See all jobs", "Unsubscribe");
        assertThat(jobs).hasSize(2);
    }

    @Test
    void fallsBackToThePlainTextVersion() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", fixture("linkedin-alert.txt"), null));

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0)).isEqualTo(new ParsedJob("3912345678", "https://www.linkedin.com/jobs/view/3912345678/", "Example Role Alpha", "Northwind Labs", "Prague, Czechia"));
        assertThat(jobs.get(1).company()).isEqualTo("Vantage Lane");
    }

    @Test
    void prefersHtmlButUsesTextWhenTheHtmlHasNoJobs() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", fixture("linkedin-alert.txt"), "<p>Redesigned template</p>"));

        assertThat(jobs).hasSize(2);
    }

    @Test
    void aChangedTemplateGivesAnEmptyListNotAnError() throws IOException {
        assertThat(parser.parse(mail("jobalerts-noreply@linkedin.com", "Hello there", "<html><p>Nothing useful</p></html>"))).isEmpty();
        assertThat(parser.parse(mail("jobalerts-noreply@linkedin.com", null, null))).isEmpty();
        assertThat(parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("other-sender.html")))).isEmpty();
    }

    @Test
    void aLinkWithoutCompanyOrPlaceStillGivesTheVacancy() {
        String html = "<table><tr><td><a href=\"https://www.linkedin.com/comm/jobs/view/3900000001/?t=1\">Lonely Title</a></td></tr></table>";

        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, html));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).title()).isEqualTo("Lonely Title");
    }

    @Test
    void onlyLinkedInDomainsAreAccepted() {
        assertThat(parser.accepts(mail("jobalerts-noreply@linkedin.com", null, null))).isTrue();
        assertThat(parser.accepts(mail("x@e.linkedin.com", null, null))).isTrue();
        assertThat(parser.accepts(mail("x@evil-linkedin.com", null, null))).isFalse();
        assertThat(parser.accepts(mail("x@linkedin.com.evil.test", null, null))).isFalse();
        assertThat(parser.accepts(mail(null, null, null))).isFalse();
    }

    @Test
    void htmlInTheEmailIsNeverExecutedOrFollowed() {
        String html = "<script>alert(1)</script><a href=\"https://www.linkedin.com/comm/jobs/view/3900000002/\">&lt;b&gt;Title&lt;/b&gt;</a>";

        List<ParsedJob> jobs = parser.parse(mail("a@linkedin.com", null, html));

        assertThat(jobs.get(0).title()).isEqualTo("<b>Title</b>");
    }

    @Test
    void lineSharedByTwoPostingsIsNotTakenAsEitherOnesCompany() {
        String html = "<div><a href=\"https://www.linkedin.com/jobs/view/3911111111/\">Role One</a>"
                + "<a href=\"https://www.linkedin.com/jobs/view/3922222222/\">Role Two</a><p>Some footer line</p></div>";

        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, html));

        assertThat(jobs).extracting(ParsedJob::title).containsExactly("Role One", "Role Two");
        assertThat(jobs).extracting(ParsedJob::company).containsOnlyNulls();
    }
}
