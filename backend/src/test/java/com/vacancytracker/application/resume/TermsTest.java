package com.vacancytracker.application.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Year;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class TermsTest {

    private static final String RESUME = """
            Jane Doe
            Senior Backend Engineer, Kyiv, Ukraine

            Summary
            Backend engineer building payment systems. Worked with Kafka and Spring Boot at Acme.

            Skills
            Java, Spring Boot, PostgreSQL, Kafka, Docker
            Languages: English (C1), Ukrainian

            Experience
            Acme Payments, 03/2019 - present
            - Built services in Java 21 and Kotlin, deployed to AWS with CI/CD
            Globex, 2015 - 2018
            - Maintained a C# and .NET Core monolith
            """;

    @Test
    void proposesSkillsSectionItemsFirstThenShapes() {
        var keywords = Terms.resumeKeywords(RESUME, 40);

        assertThat(keywords).startsWith("Java", "Spring Boot", "PostgreSQL", "Kafka", "Docker");
        assertThat(keywords).contains("C#", ".NET Core", "AWS", "CI/CD", "English (C1)");
        assertThat(keywords).doesNotContain("Ukraine", "Summary", "Experience");
    }

    @Test
    void shapesRecogniseTechnicalTokensButNotPlainWords() {
        assertThat(Terms.strongShape("C#")).isTrue();
        assertThat(Terms.strongShape("C++")).isTrue();
        assertThat(Terms.strongShape("Node.js")).isTrue();
        assertThat(Terms.strongShape("CI/CD")).isTrue();
        assertThat(Terms.strongShape("PostgreSQL")).isTrue();
        assertThat(Terms.strongShape("AWS")).isTrue();
        assertThat(Terms.strongShape("S3")).isTrue();
        assertThat(Terms.strongShape("Python")).isFalse();
        assertThat(Terms.strongShape("2024")).isFalse();
    }

    @Test
    void capitalisedWordsCountOnlyInsideEnumerationsAndNotAtTheirStart() {
        var terms = Terms.skillLikeTerms("""
                Requirements:
                - Experience with Python, Django and Redis
                We are a friendly Team in Berlin.
                """);

        assertThat(terms).contains("Python", "Django", "Redis");
        assertThat(terms).doesNotContain("Experience", "We", "Team", "Berlin");
    }

    @Test
    void mentionsMatchesWholeTermsIgnoringCaseAndDotlessSpellings() {
        assertThat(Terms.mentions("We use NodeJS and react", "Node.js")).isTrue();
        assertThat(Terms.mentions("We use React", "react")).isTrue();
        assertThat(Terms.mentions("Experience with Javascript", "Java")).isFalse();
        assertThat(Terms.mentions("C# developer", "C")).isFalse();
        assertThat(Terms.mentions("spring-boot services", "Spring Boot")).isTrue();
        assertThat(Terms.mentions(null, "Java")).isFalse();
    }

    @Test
    void experienceMergesOverlappingRangesAndReadsStatedYears() {
        assertThat(Experience.ofResume(RESUME, Year.of(2026))).isEqualTo(OptionalInt.of(10));
        assertThat(Experience.ofResume("I have 7 years of experience", Year.of(2026))).isEqualTo(OptionalInt.of(7));
        assertThat(Experience.ofResume("No dates here", Year.of(2026))).isEmpty();
        assertThat(Experience.required("You have 5+ years with Java and 2 years with Go")).isEqualTo(OptionalInt.of(2));
        assertThat(Experience.required("Досвід від 3 років")).isEqualTo(OptionalInt.of(3));
    }
}
