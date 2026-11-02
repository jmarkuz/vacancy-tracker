package com.vacancytracker.application.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FactCheckTest {

    static final String BASE = """
            # Jane Doe
            Kyiv | jane@example.com

            ## Experience
            ### Backend Engineer, Acme Pay, Remote
            Jan 2020 to Present
            - Cut payment latency by 40% with Java and PostgreSQL for 1,200 merchants.
            - Led a team of 5 engineers.

            ## Skills
            Java, PostgreSQL, Docker
            """;

    private static List<String> values(List<FactCheck.Finding> findings, String category) {
        return findings.stream().filter(f -> f.category().equals(category)).map(FactCheck.Finding::value).toList();
    }

    @Test
    void anUnchangedOrReorderedTextHasNoFindings() {
        assertThat(FactCheck.check(BASE, BASE, "Beta Bank", "Senior Java Engineer")).isEmpty();
        String reordered = BASE.replace("- Cut payment latency by 40% with Java and PostgreSQL for 1200 merchants.", "")
                + "\n- Cut payment latency by 40% with Java and PostgreSQL for 1200 merchants.";
        assertThat(FactCheck.check(BASE, reordered, null, null)).isEmpty();
    }

    @Test
    void newNumbersPercentagesAndDatesAreFound() {
        String generated = BASE.replace("by 40%", "by 65%").replace("team of 5", "team of 12").replace("Jan 2020", "Mar 2018")
                + "\n- Saved $2m a year.";
        List<FactCheck.Finding> findings = FactCheck.check(BASE, generated, null, null);

        assertThat(values(findings, FactCheck.NUMBER)).containsExactlyInAnyOrder("65%", "12", "$2m");
        assertThat(values(findings, FactCheck.DATE)).containsExactly("Mar 2018");
        assertThat(findings.get(0).line()).isNotBlank();
    }

    @Test
    void newEmployersTitlesAndTechnologiesAreFoundButNotTheVacancysOwnName() {
        String generated = BASE.replace("### Backend Engineer, Acme Pay, Remote", "### Staff Engineer, Globex, Remote")
                + "\n- Ran Kubernetes and Terraform for the platform.\n\nI am applying for the Senior Java Engineer role at Beta Bank.";
        List<FactCheck.Finding> findings = FactCheck.check(BASE, generated, "Beta Bank", "Senior Java Engineer");

        assertThat(values(findings, FactCheck.TITLE)).containsExactly("Staff Engineer");
        assertThat(values(findings, FactCheck.COMPANY)).containsExactly("Globex");
        assertThat(values(findings, FactCheck.TECHNOLOGY)).contains("Kubernetes", "Terraform").doesNotContain("Java", "Docker");
        assertThat(findings).noneMatch(f -> f.value().contains("Beta Bank"));
    }

    @Test
    void theCoverLetterIsCheckedAgainstTheBaseLetterAndTheBaseResume() {
        String base = "Dear Hiring Manager,\n\nI build payment systems.\n\n" + BASE;
        String letter = "Dear Hiring Manager,\n\nI am applying for the Senior Java Engineer role at Beta Bank.\n\n"
                + "At Acme Pay I cut payment latency by 40% for 1,200 merchants. Before that I worked at Initech for 3 years.";
        List<FactCheck.Finding> findings = FactCheck.check(base, letter, "Beta Bank", "Senior Java Engineer");

        assertThat(values(findings, FactCheck.COMPANY)).containsExactly("Initech");
        assertThat(values(findings, FactCheck.NUMBER)).containsExactly("3");
    }
}
