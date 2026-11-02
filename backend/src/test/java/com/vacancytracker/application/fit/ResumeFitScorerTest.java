package com.vacancytracker.application.fit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ResumeFitScorerTest {

    private static final List<String> KEYWORDS = List.of("Java", "Spring Boot", "PostgreSQL", "Kafka");
    private static final String RESUME = "Java, Spring Boot, PostgreSQL, Kafka. Some Docker.";
    private static final String DESCRIPTION = """
            We build payment services.
            Requirements:
            - 5+ years of experience with Java, Spring Boot, PostgreSQL
            - Familiar with Docker, Kubernetes, AWS and Kafka
            """ + "We offer a friendly team and growth. ".repeat(5);

    @Test
    void explainsMatchedAndMissingSkillsAndYears() {
        var result = ResumeFitScorer.score(KEYWORDS, OptionalInt.of(7), RESUME, "Senior Java Engineer", DESCRIPTION);

        assertThat(result.matched()).contains("Java", "Spring Boot", "PostgreSQL", "Kafka", "Docker");
        assertThat(result.missing()).containsExactlyInAnyOrder("Kubernetes", "AWS");
        // 5 of 7 asked, 85 x 5/7 = 61, +15 title
        assertThat(result.score()).isEqualTo(76);
        assertThat(result.reasons()).extracting(ResumeFitScorer.Reason::kind).containsExactly("matched", "missing", "title", "years");
        assertThat(result.reasons().get(3).text()).isEqualTo("Asks for 5+ years; your resume shows about 7.");
    }

    @Test
    void fewerYearsThanAskedLowersTheScore() {
        var enough = ResumeFitScorer.score(KEYWORDS, OptionalInt.of(7), RESUME, "Engineer", DESCRIPTION);
        var fewer = ResumeFitScorer.score(KEYWORDS, OptionalInt.of(2), RESUME, "Engineer", DESCRIPTION);

        assertThat(enough.score() - fewer.score()).isEqualTo(ResumeFitScorer.YEARS_PENALTY);
        assertThat(fewer.reasons()).anyMatch(r -> r.text().endsWith("fewer than asked."));
    }

    @Test
    void moreMatchingSkillsNeverLowerTheScore() {
        var some = ResumeFitScorer.score(List.of("Java"), OptionalInt.empty(), "Java", "Engineer", DESCRIPTION);
        var more = ResumeFitScorer.score(List.of("Java", "Kubernetes"), OptionalInt.empty(), "Java Kubernetes", "Engineer", DESCRIPTION);

        assertThat(more.score()).isGreaterThan(some.score());
    }

    @Test
    void aVacancyWithoutSkillsScoresZeroAndSaysWhy() {
        var result = ResumeFitScorer.score(KEYWORDS, OptionalInt.empty(), RESUME, "Office manager", "Join us.");

        assertThat(result.score()).isZero();
        assertThat(result.reasons()).singleElement().extracting(ResumeFitScorer.Reason::kind).isEqualTo("thin");
    }

    @Test
    void aShortVacancyIsMarkedAsScoredOnLittleText() {
        var result = ResumeFitScorer.score(KEYWORDS, OptionalInt.empty(), RESUME, "Java developer", null);

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.reasons()).extracting(ResumeFitScorer.Reason::kind).contains("thin");
    }

    @Test
    void longListsAreShortened() {
        List<String> many = List.of("A1", "B2", "C3", "D4", "E5", "F6", "G7", "H8", "I9", "J10");
        var result = ResumeFitScorer.score(many, OptionalInt.empty(), "", "Engineer", String.join(", ", many));

        assertThat(result.reasons().get(0).text()).contains("and 2 more");
    }
}
