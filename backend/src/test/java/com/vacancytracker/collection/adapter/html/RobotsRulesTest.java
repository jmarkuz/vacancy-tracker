package com.vacancytracker.collection.adapter.html;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsRulesTest {

    private static RobotsRules rules(String robots) {
        return RobotsRules.parse(robots, "VacancyTracker");
    }

    @Test
    void aByteOrderMarkDoesNotHideTheFirstGroup() {
        RobotsRules rules = RobotsRules.parse("\uFEFFUser-agent: *\nDisallow: /\n", "VacancyTracker");

        assertThat(rules.isAllowed("/jobs")).isFalse();
    }

    @Test
    void anEmptyFileAndAnEmptyDisallowAllowEverything() {
        assertThat(rules("").isAllowed("/jobs")).isTrue();
        assertThat(rules("User-agent: *\nDisallow:\n").isAllowed("/anything")).isTrue();
        assertThat(RobotsRules.allowAll().isAllowed("/x")).isTrue();
    }

    @Test
    void disallowBlocksThePrefixOnly() {
        RobotsRules r = rules("User-agent: *\nDisallow: /private\n");

        assertThat(r.isAllowed("/private")).isFalse();
        assertThat(r.isAllowed("/private/jobs?page=2")).isFalse();
        assertThat(r.isAllowed("/privately")).isFalse();
        assertThat(r.isAllowed("/jobs")).isTrue();
    }

    @Test
    void theLongestMatchWinsAndAllowWinsATie() {
        RobotsRules r = rules("User-agent: *\nDisallow: /jobs\nAllow: /jobs/open\n");

        assertThat(r.isAllowed("/jobs/closed")).isFalse();
        assertThat(r.isAllowed("/jobs/open/1")).isTrue();
        RobotsRules tie = rules("User-agent: *\nDisallow: /a\nAllow: /a\n");
        assertThat(tie.isAllowed("/a")).isTrue();
    }

    @Test
    void wildcardsAndEndAnchorsWork() {
        RobotsRules r = rules("User-agent: *\nDisallow: /*.pdf$\nDisallow: /search*sort=\nDisallow: *tracking\n");

        assertThat(r.isAllowed("/files/cv.pdf")).isFalse();
        assertThat(r.isAllowed("/files/cv.pdf.html")).isTrue();
        assertThat(r.isAllowed("/search?q=a&sort=new")).isFalse();
        assertThat(r.isAllowed("/search?q=a")).isTrue();
        assertThat(r.isAllowed("/x/tracking")).isFalse();
    }

    @Test
    void theMostSpecificAgentGroupWinsOverStar() {
        String file = "User-agent: *\nDisallow: /\n\nUser-agent: vacancytracker\nAllow: /\nDisallow: /admin\n";
        RobotsRules r = rules(file);

        assertThat(r.isAllowed("/jobs")).isTrue();
        assertThat(r.isAllowed("/admin")).isFalse();
        assertThat(RobotsRules.parse(file, "OtherBot").isAllowed("/jobs")).isFalse();
    }

    @Test
    void severalAgentLinesShareOneGroupAndAgentNamesMatchWithoutCase() {
        RobotsRules r = rules("User-agent: Googlebot\nUser-agent: VACANCYTRACKER\nDisallow: /nope\n");

        assertThat(r.isAllowed("/nope")).isFalse();
        assertThat(r.isAllowed("/yes")).isTrue();
    }

    @Test
    void aFileWithOnlyOtherAgentsAllowsUs() {
        assertThat(rules("User-agent: Googlebot\nDisallow: /\n").isAllowed("/jobs")).isTrue();
    }

    @Test
    void commentsBlankLinesAndUnknownFieldsAreIgnored() {
        RobotsRules r = rules("# hello\nUser-agent: * # everyone\nSitemap: https://x.test/s.xml\nDisallow: /x # no\n\nHost: x.test\n");

        assertThat(r.isAllowed("/x")).isFalse();
        assertThat(r.isAllowed("/y")).isTrue();
    }

    @Test
    void crawlDelayIsReadFromTheGroupThatApplies() {
        assertThat(rules("User-agent: *\nCrawl-delay: 10\n").crawlDelaySeconds()).isEqualTo(10);
        assertThat(rules("User-agent: *\nCrawl-delay: 10\n\nUser-agent: vacancytracker\nCrawl-delay: 2.5\n").crawlDelaySeconds()).isEqualTo(3);
        assertThat(rules("User-agent: *\nCrawl-delay: soon\n").crawlDelaySeconds()).isZero();
        assertThat(rules("User-agent: *\nDisallow: /a\n").crawlDelaySeconds()).isZero();
    }
}
