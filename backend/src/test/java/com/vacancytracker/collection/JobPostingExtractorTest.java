package com.vacancytracker.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JobPostingExtractorTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = JobPostingExtractorTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void extractsJobPostingFromGraphWithSalaryAndRemoteFlag() throws IOException {
        var job = JobPostingExtractor.extract(fixture("job-jsonld.html")).orElseThrow();

        assertThat(job.title()).isEqualTo("Backend Engineer (Java)");
        assertThat(job.company()).isEqualTo("Northwind Labs");
        assertThat(job.location()).isEqualTo("Berlin, DE");
        assertThat(job.workMode()).isEqualTo("remote");
        assertThat(job.description()).isEqualTo("Build APIs for our platform.");
        assertThat(job.salaryMin()).isEqualByComparingTo("70000");
        assertThat(job.salaryMax()).isEqualByComparingTo("85000");
        assertThat(job.currency()).isEqualTo("EUR");
        assertThat(job.salaryPeriod()).isEqualTo("year");
    }

    @Test
    void invalidJsonAndMissingPostingReturnEmpty() throws IOException {
        assertThat(JobPostingExtractor.extract(fixture("no-job.html"))).isEmpty();
        assertThat(JobPostingExtractor.extract("<html></html>")).isEmpty();
    }

    @Test
    void singleObjectWithoutGraphIsAccepted() {
        String html = """
                <script type="application/ld+json">{"@type":"JobPosting","title":"QA","hiringOrganization":{"name":"Acme"}}</script>
                """;
        var job = JobPostingExtractor.extract(html).orElseThrow();
        assertThat(job.title()).isEqualTo("QA");
        assertThat(job.company()).isEqualTo("Acme");
        assertThat(job.salaryMin()).isNull();
    }
}
