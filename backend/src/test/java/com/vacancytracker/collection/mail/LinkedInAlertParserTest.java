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
    void postingsInOneBlockTakeTheTextBetweenTheirOwnLink() {
        String html = "<div><a href=\"https://www.linkedin.com/jobs/view/3911111111/\">Role One</a><span>Northwind</span><span>Prague</span>"
                + "<a href=\"https://www.linkedin.com/jobs/view/3922222222/\">Role Two</a><span>Vantage</span><span>Remote</span></div>";

        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, html));

        assertThat(jobs).extracting(ParsedJob::company).containsExactly("Northwind", "Vantage");
        assertThat(jobs).extracting(ParsedJob::location).containsExactly("Prague", "Remote");
    }

    private static ParsedJob byId(List<ParsedJob> jobs, String id) {
        return jobs.stream().filter(j -> j.externalId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aCardThatIsOneLinkGivesTheTitleAloneAndTheCompanyAndPlaceApart() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("linkedin-alert-cards.html")));

        assertThat(jobs).hasSize(4);
        ParsedJob lines = byId(jobs, "4300000001");
        assertThat(lines.title()).isEqualTo("Java Developer (43917)");
        assertThat(lines.company()).isEqualTo("CoolPeople Technology");
        assertThat(lines.location()).isEqualTo("Prague");
    }

    @Test
    void aCardWithASingleTextIsSplitAfterItsTitleOnlyWhenTheTitleIsKnown() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("linkedin-alert-cards.html")));

        // One link with one text: no way to tell where the title ends, so nothing is invented; the whole text stays the title.
        ParsedJob single = byId(jobs, "4300000002");
        assertThat(single.title()).startsWith("Senior Java Developer Barclays");
        assertThat(single.company()).isNull();
    }

    @Test
    void theShorterTitleLinkNamesTheTitleAndTheLongerCardLinkGivesTheCompanyAndPlace() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("linkedin-alert-cards.html")));

        ParsedJob both = byId(jobs, "4300000003");
        assertThat(both.title()).isEqualTo("Platform Engineer (Java)");
        assertThat(both.company()).isEqualTo("Revolut");
        assertThat(both.location()).isEqualTo("Moravia-Silesia, Czechia (Віддалена робота)");
    }

    @Test
    void aCompanyAndPlaceLineIsSplitEvenWhenABadgeLineFollowsIt() throws IOException {
        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", null, fixture("linkedin-alert-cards.html")));

        ParsedJob badge = byId(jobs, "4300000004");
        assertThat(badge.title()).isEqualTo("Java Developer");
        assertThat(badge.company()).isEqualTo("Playtech");
        assertThat(badge.location()).isEqualTo("Kyiv, Ukraine (Віддалена робота)");
    }

    @Test
    void companyAndLocationReadsTwoLinesOrOneLineWithTheSeparator() {
        assertThat(LinkAlertParser.companyAndLocation(List.of("Acme", "Berlin"))).containsExactly("Acme", "Berlin");
        assertThat(LinkAlertParser.companyAndLocation(List.of("Acme · Berlin"))).containsExactly("Acme", "Berlin");
        assertThat(LinkAlertParser.companyAndLocation(List.of("Acme · Berlin", "Actively hiring"))).containsExactly("Acme", "Berlin");
        assertThat(LinkAlertParser.companyAndLocation(List.of("Acme"))).containsExactly("Acme", null);
        assertThat(LinkAlertParser.companyAndLocation(List.of("Acme · "))).containsExactly("Acme", null);
        assertThat(LinkAlertParser.companyAndLocation(List.of())).containsExactly(null, null);
        assertThat(LinkAlertParser.companyAndLocation(List.of("Acme Pay · Co · Berlin"))).containsExactly("Acme Pay", "Co · Berlin");
    }

    @Test
    void theTextVersionAlsoSplitsACompanyAndPlaceLine() {
        String text = "Backend Engineer\nAcme · Berlin\nHybrid\nhttps://www.linkedin.com/jobs/view/4300000009/\n";

        List<ParsedJob> jobs = parser.parse(mail("jobalerts-noreply@linkedin.com", text, null));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).title()).isEqualTo("Backend Engineer");
        assertThat(jobs.get(0).company()).isEqualTo("Acme");
        assertThat(jobs.get(0).location()).isEqualTo("Berlin");
    }
}
