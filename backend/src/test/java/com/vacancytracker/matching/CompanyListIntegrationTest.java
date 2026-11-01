package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.ProfilePreviewService.Preview;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CompanyListIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private CompanyListService lists;
    @Autowired
    private MatchService matchService;
    @Autowired
    private SearchProfileService profiles;
    @Autowired
    private ProfilePreviewService previews;
    @Autowired
    private VacancyRepository vacancies;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE search_profile, company_list CASCADE");
    }

    private ProfileInput input(String name, int inboxMin) throws Exception {
        Criterion rule = new Criterion("java", "title", "contains_any", mapper.readTree("[\"java\"]"), "prefer", 5, null, "Java");
        return new ProfileInput(name, true, List.of("all"), null, new Thresholds(inboxMin, 70, 60), List.of(rule));
    }

    private UUID vacancy(String title, String company) {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), title, company, "https://ex.com/" + UUID.randomUUID(), null, "Plain", NOW);
        vacancies.save(v.toState());
        return v.getId();
    }

    private String status(UUID id) {
        return template.queryForObject("SELECT status FROM vacancy WHERE id = ?", String.class, id);
    }

    @Test
    void aBlockedCompanyIsHiddenWithAReasonEvenWhenTheRulesLikeIt() throws Exception {
        SearchProfile p = profiles.create(input("P", 40));
        UUID id = vacancy("Java Developer", "Bad Corp");
        UUID ok = vacancy("Java Developer", "Good Corp");
        lists.add("bad corp", "blocklist", null);

        VacancyMatch m = matchService.match(id).get(0);
        matchService.match(ok);

        assertThat(m.hidden()).isTrue();
        assertThat(m.score()).isNull();
        assertThat(m.reasons()).extracting(MatchReason::kind).contains("blocklist");
        assertThat(status(id)).isEqualTo("Hidden");
        assertThat(status(ok)).isEqualTo("New");
        assertThat(p.id()).isNotNull();
    }

    @Test
    void anAllowedCompanyGetsTheBonusAndStaysInTheInboxBelowTheThreshold() throws Exception {
        profiles.create(input("P", 40));
        UUID plain = vacancy("Cobol Developer", "Other Corp");
        UUID allowed = vacancy("Cobol Developer", "Dear Corp");
        lists.add("Dear Corp", "allowlist", null);

        matchService.match(plain);
        VacancyMatch m = matchService.match(allowed).get(0);

        assertThat(status(plain)).isEqualTo("Hidden");
        assertThat(status(allowed)).isEqualTo("New");
        assertThat(m.score()).isEqualTo(67);
        assertThat(m.reasons()).extracting(MatchReason::kind).contains("allowlist");
    }

    @Test
    void aProfileListAffectsOnlyThatProfile() throws Exception {
        SearchProfile one = profiles.create(input("One", 0));
        SearchProfile two = profiles.create(input("Two", 0));
        UUID id = vacancy("Java Developer", "Acme");
        lists.add("Acme", "blocklist", one.id());

        List<VacancyMatch> matches = matchService.match(id);

        assertThat(matches).hasSize(2);
        assertThat(matches.stream().filter(x -> x.profileId().equals(one.id())).findFirst().orElseThrow().hidden()).isTrue();
        assertThat(matches.stream().filter(x -> x.profileId().equals(two.id())).findFirst().orElseThrow().hidden()).isFalse();
        assertThat(status(id)).isEqualTo("New");
    }

    @Test
    void addingTheSameCompanyAgainIsIdempotentAndRemovingWorks() {
        CompanyListEntry first = lists.add("Acme Corp", "blocklist", null);
        CompanyListEntry again = lists.add("  ACME   corp ", "blocklist", null);
        lists.add("Acme Corp", "allowlist", null);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(lists.list()).hasSize(2);

        lists.remove(first.id());
        assertThat(lists.list()).hasSize(1);
        assertThatThrownBy(() -> lists.remove(first.id())).isInstanceOf(com.vacancytracker.common.NotFoundException.class);
    }

    @Test
    void badInputIsRejected() {
        assertThatThrownBy(() -> lists.add(" ", "blocklist", null)).isInstanceOf(ProfileValidationException.class);
        assertThatThrownBy(() -> lists.add("A", "greylist", null)).isInstanceOf(ProfileValidationException.class);
        assertThatThrownBy(() -> lists.add("x".repeat(201), "allowlist", null)).isInstanceOf(ProfileValidationException.class);
        assertThatThrownBy(() -> lists.add("A", "allowlist", UUID.randomUUID())).isInstanceOf(com.vacancytracker.common.NotFoundException.class);
    }

    @Test
    void deletingAProfileDeletesItsEntriesButNotGlobalOnes() throws Exception {
        SearchProfile p = profiles.create(input("P", 40));
        lists.add("Mine", "allowlist", p.id());
        lists.add("Global", "blocklist", null);

        profiles.delete(p.id());

        assertThat(lists.list()).extracting(CompanyListEntry::company).containsExactly("Global");
    }

    @Test
    void thePreviewHonoursTheLists() throws Exception {
        vacancy("Java Developer", "Bad Corp");
        vacancy("Java Developer", "Good Corp");
        lists.add("Bad Corp", "blocklist", null);

        Preview p = previews.preview(input("Draft", 40));

        assertThat(p.filteredOut()).isEqualTo(1);
        assertThat(p.inbox()).isEqualTo(1);
    }
}
