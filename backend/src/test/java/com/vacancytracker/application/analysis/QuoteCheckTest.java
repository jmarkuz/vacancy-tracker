package com.vacancytracker.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteCheckTest {

    private static final String RESUME = """
            # Jane Doe

            ## Experience
            ### Backend Engineer, Acme
            - Cut payment latency by 40% with Java   and PostgreSQL.

            ## Skills
            Languages: Java, Kotlin
            Infrastructure and data: Kafka, PostgreSQL
            """;

    private static ObjectNode proposed(String status, String quote) {
        return new ObjectMapper().createObjectNode().put("status", status).put("quote", quote);
    }

    private static final Checklist.Requirement KAFKA = new Checklist.Requirement("r1", "Experience with Kafka", Checklist.MUST, List.of("Kafka"));

    @Test
    void aQuoteMustBeInTheDocumentWhitespaceAside() {
        assertThat(QuoteCheck.place(RESUME, "with Java and PostgreSQL")).isEqualTo(QuoteCheck.Place.EVIDENCE);
        assertThat(QuoteCheck.place(RESUME, "led a team of 12")).isEqualTo(QuoteCheck.Place.NOT_FOUND);
        assertThat(QuoteCheck.place(RESUME, "")).isEqualTo(QuoteCheck.Place.NOT_FOUND);
    }

    @Test
    void aMentionOnlyInSkillsIsNotProof() {
        assertThat(QuoteCheck.place(RESUME, "Kafka")).isEqualTo(QuoteCheck.Place.SKILLS_ONLY);
        assertThat(QuoteCheck.place(RESUME, "PostgreSQL")).isEqualTo(QuoteCheck.Place.EVIDENCE);
        assertThat(QuoteCheck.place("Jane\nSkills: Java, Kafka\nBuilt things.", "Kafka")).isEqualTo(QuoteCheck.Place.SKILLS_ONLY);
        assertThat(QuoteCheck.place("Jane Doe\n\nSkills\nJava, Kafka\n\nAcme Bank, 2019 - 2025\nRan Kafka clusters.", "Kafka"))
                .isEqualTo(QuoteCheck.Place.EVIDENCE);
        assertThat(QuoteCheck.place("Jane Doe\n\nSkills\nJava, Kafka\n\nAcme Bank, 2019 - 2025", "Kafka"))
                .isEqualTo(QuoteCheck.Place.SKILLS_ONLY);
    }

    @Test
    void theServerLowersWhatTheModelCannotProve() {
        // A keyword in Skills without proof: at most partial, with the reason.
        EmployerMatch.Item skills = AnalysisService.check(KAFKA, proposed("covered", "Kafka"), RESUME);
        assertThat(skills.status()).isEqualTo(EmployerMatch.PARTIAL);
        assertThat(skills.note()).contains("only in Skills");
        // An invented quote: missing.
        EmployerMatch.Item invented = AnalysisService.check(KAFKA, proposed("covered", "Ran Kafka at scale"), RESUME);
        assertThat(invented.status()).isEqualTo(EmployerMatch.MISSING);
        assertThat(invented.quote()).isNull();
        // Proof in experience: covered stays covered; an unknown status is missing.
        String withProof = RESUME.replace("- Cut payment", "- Moved billing events to Kafka.\n- Cut payment");
        assertThat(AnalysisService.check(KAFKA, proposed("covered", "billing events to Kafka"), withProof).status()).isEqualTo(EmployerMatch.COVERED);
        assertThat(AnalysisService.check(KAFKA, proposed("excellent", "Kafka"), withProof).status()).isEqualTo(EmployerMatch.MISSING);
        assertThat(AnalysisService.check(KAFKA, null, withProof).status()).isEqualTo(EmployerMatch.MISSING);
    }

    @Test
    void theScoreIsTheWeightedShareOfCoveredRequirements() {
        List<Checklist.Requirement> requirements = List.of(
                new Checklist.Requirement("r1", "a", Checklist.MUST, List.of()),
                new Checklist.Requirement("r2", "b", Checklist.IMPORTANT, List.of()),
                new Checklist.Requirement("r3", "c", Checklist.NICE, List.of()));
        List<EmployerMatch.Item> items = List.of(
                new EmployerMatch.Item("r1", "a", Checklist.MUST, EmployerMatch.COVERED, "x", null),
                new EmployerMatch.Item("r2", "b", Checklist.IMPORTANT, EmployerMatch.PARTIAL, "y", null),
                new EmployerMatch.Item("r3", "c", Checklist.NICE, EmployerMatch.MISSING, null, null));
        // round(100 × (3×1 + 2×0.5 + 1×0) / 6) = 67
        assertThat(EmployerMatch.score(requirements, items)).isEqualTo(67);
        assertThat(EmployerMatch.score(List.of(), List.of())).isZero();
    }

    @Test
    void theGoalScoreIsTheAverageWeightedByTheNormalisedShares() {
        List<GoalMatch.Parameter> parameters = List.of(
                new GoalMatch.Parameter("Stack", 3, 0.75, 80, ""),
                new GoalMatch.Parameter("Pay", 1, 0.25, 40, ""));
        assertThat(GoalMatch.score(parameters)).isEqualTo(70);
    }
}
