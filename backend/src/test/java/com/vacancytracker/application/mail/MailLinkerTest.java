package com.vacancytracker.application.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.mail.MailMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MailLinker's matching and its pattern cache. */
class MailLinkerTest {

    private static MailMessage mail(String from, String subject, String body) {
        return new MailMessage("<k@x>", from, "Sender", subject, null, body, null);
    }

    private static MailLinker.Candidate candidate(UUID id, String title, String company, String url) {
        return new MailLinker.Candidate(id, title, company, url, false);
    }

    @Test
    void aCompanyNameMustStandAloneNotInsideAnotherWord() {
        UUID acme = UUID.randomUUID();
        List<MailLinker.Candidate> candidates = List.of(candidate(acme, "Backend Engineer", "Acme", "https://jobs.example/1"));

        assertThat(MailLinker.link(mail("hr@x.example", "Interview with Acme", "Hi"), candidates)).map(MailLinker.Link::vacancyId).contains(acme);
        assertThat(MailLinker.link(mail("hr@x.example", "Acmeville news", "Hi"), candidates)).isEmpty();
        assertThat(MailLinker.link(mail("hr@x.example", "Hello", "We at Acme-Pay"), candidates)).map(MailLinker.Link::vacancyId).contains(acme);
    }

    @Test
    void aMultiWordCompanyMatchesAcrossSpacesAndPunctuation() {
        UUID id = UUID.randomUUID();
        List<MailLinker.Candidate> candidates = List.of(candidate(id, "Data Engineer", "Deutsche Börse Group", "https://jobs.example/2"));

        Optional<MailLinker.Link> link = MailLinker.link(mail("jobs@x.example", "Your job", "Saved job at Deutsche, Börse"), candidates);

        assertThat(link).map(MailLinker.Link::vacancyId).contains(id);
    }

    @Test
    void theSameAnswerComesFromTheCacheAndFromAFreshPattern() {
        UUID id = UUID.randomUUID();
        List<MailLinker.Candidate> candidates = List.of(candidate(id, "Platform Engineer", "Zeta Corp", "https://jobs.example/3"));
        MailMessage m = mail("hr@x.example", "Offer from Zeta Corp", "Hi");

        Optional<MailLinker.Link> first = MailLinker.link(m, candidates);
        int cachedAfterFirst = MailLinker.cachedPatterns();
        Optional<MailLinker.Link> second = MailLinker.link(m, candidates);

        assertThat(second).isEqualTo(first);
        assertThat(MailLinker.cachedPatterns()).isEqualTo(cachedAfterFirst);
    }

    @Test
    void thePatternCacheStaysBoundedWhateverTheNumberOfCompanies() {
        List<MailLinker.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < MailLinker.PATTERN_CACHE_SIZE + 500; i++) {
            candidates.add(candidate(UUID.randomUUID(), "Role", "Company" + i + " Labs", "https://jobs.example/" + i));
        }

        MailLinker.link(mail("hr@x.example", "Hello from Company7 Labs", "Hi"), candidates);

        assertThat(MailLinker.cachedPatterns()).isLessThanOrEqualTo(MailLinker.PATTERN_CACHE_SIZE);
    }
}
