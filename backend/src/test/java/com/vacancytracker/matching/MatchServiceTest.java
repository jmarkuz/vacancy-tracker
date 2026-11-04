package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.AutomatedFields;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MatchServiceTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private MatchService matchService;

    @Autowired
    private SearchProfileService profiles;

    @Autowired
    private VacancyRepository vacancies;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE search_profile CASCADE");
    }

    private Criterion c(String id, String field, String op, String value, String mode, int weight) {
        try {
            return new Criterion(id, field, op, mapper.readTree(value), mode, weight, null, "Label " + id);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private SearchProfile profile(String name, List<String> sources, boolean enabled, Criterion... criteria) {
        return profiles.create(new ProfileInput(name, enabled, sources, null, Thresholds.defaults(), List.of(criteria)));
    }

    private UUID vacancy(String title, String adapter, BigDecimal salary) {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), title, "Acme", "https://ex.com/" + UUID.randomUUID(), null, "Plain", NOW);
        if (salary != null) {
            v.applyAutomated(new AutomatedFields(null, salary, salary, "EUR", "year", NOW));
        }
        vacancies.save(v.toState());
        vacancies.upsertSource(v.getId(), adapter, UUID.randomUUID().toString(), "https://ex.com/x", NOW);
        return v.getId();
    }

    private Integer storedMatchScore(UUID id) {
        return template.queryForObject("SELECT match_score FROM vacancy WHERE id = ?", Integer.class, id);
    }

    @Test
    void scoresAVacancyAgainstAProfileAndStoresScoreAndReasons() {
        SearchProfile p = profile("Main", List.of("all"), true,
                c("kw", "title", "contains_any", "[\"java\"]", "prefer", 8),
                c("pay", "salary", "gte", "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}", "prefer", 2));
        UUID id = vacancy("Java Developer", "ats-lever", new BigDecimal("60000"));

        List<VacancyMatch> matches = matchService.match(id);

        assertThat(matches).hasSize(1);
        VacancyMatch m = matches.get(0);
        assertThat(m.profileId()).isEqualTo(p.id());
        assertThat(m.profileVersion()).isEqualTo(1);
        assertThat(m.score()).isEqualTo(100);
        assertThat(m.hidden()).isFalse();
        assertThat(m.reasons()).extracting(MatchReason::criterionId).containsExactly("kw", "pay");
        assertThat(storedMatchScore(id)).isEqualTo(100);
    }

    @Test
    void theVacancyScoreIsTheBestAcrossProfiles() {
        profile("Low", List.of("all"), true, c("a", "title", "contains_any", "[\"cobol\"]", "prefer", 5));
        profile("High", List.of("all"), true, c("b", "title", "contains_any", "[\"java\"]", "prefer", 5));
        UUID id = vacancy("Java Developer", "ats-lever", null);

        List<VacancyMatch> matches = matchService.match(id);

        assertThat(matches).extracting(VacancyMatch::score).containsExactly(100, 0);
        assertThat(storedMatchScore(id)).isEqualTo(100);
    }

    @Test
    void aFilteredMatchHasNoScoreAndDoesNotCountTowardTheBest() {
        profile("Main", List.of("all"), true, c("kw", "title", "contains_any", "[\"java\"]", "prefer", 5));
        profile("Strict", List.of("all"), true, c("ban", "title", "contains_any", "[\"intern\"]", "exclude", 5));
        UUID id = vacancy("Java Intern", "ats-lever", null);

        List<VacancyMatch> matches = matchService.match(id);

        VacancyMatch hidden = matches.stream().filter(VacancyMatch::hidden).findFirst().orElseThrow();
        assertThat(hidden.score()).isNull();
        assertThat(hidden.reasons().get(0).kind()).isEqualTo("exclude");
        assertThat(storedMatchScore(id)).isEqualTo(100);
    }

    @Test
    void ifEveryProfileFiltersTheVacancyItHasNoMatchScore() {
        profile("Strict", List.of("all"), true, c("ban", "title", "contains_any", "[\"intern\"]", "exclude", 5));
        UUID id = vacancy("Java Intern", "ats-lever", null);

        matchService.match(id);

        assertThat(storedMatchScore(id)).isNull();
    }

    @Test
    void aProfileOnlyAppliesToItsOwnSources() {
        profile("Lever only", List.of("ats-lever"), true, c("a", "title", "contains_any", "[\"java\"]", "prefer", 5));
        profile("Arbeitnow only", List.of("arbeitnow"), true, c("b", "title", "contains_any", "[\"java\"]", "prefer", 5));
        UUID id = vacancy("Java Developer", "ats-lever", null);

        List<VacancyMatch> matches = matchService.match(id);

        assertThat(matches).hasSize(1);
        assertThat(profiles.get(matches.get(0).profileId()).name()).isEqualTo("Lever only");
    }

    @Test
    void disabledProfilesAreIgnoredAndTheirOldMatchesAreRemoved() {
        SearchProfile p = profile("Main", List.of("all"), true, c("a", "title", "contains_any", "[\"java\"]", "prefer", 5));
        UUID id = vacancy("Java Developer", "ats-lever", null);
        assertThat(matchService.match(id)).hasSize(1);

        profiles.update(p.id(), new ProfileInput("Main", false, List.of("all"), null, Thresholds.defaults(), p.criteria()));

        assertThat(matchService.match(id)).isEmpty();
        assertThat(storedMatchScore(id)).isNull();
    }

    @Test
    void matchingAgainReplacesTheRowAndRecordsTheNewProfileVersion() {
        SearchProfile p = profile("Main", List.of("all"), true, c("a", "title", "contains_any", "[\"cobol\"]", "prefer", 5));
        UUID id = vacancy("Java Developer", "ats-lever", null);
        assertThat(matchService.match(id).get(0).score()).isZero();

        profiles.update(p.id(), new ProfileInput("Main", true, List.of("all"), null, Thresholds.defaults(),
                List.of(c("a", "title", "contains_any", "[\"java\"]", "prefer", 5))));
        List<VacancyMatch> again = matchService.match(id);

        assertThat(again).hasSize(1);
        assertThat(again.get(0).profileVersion()).isEqualTo(2);
        assertThat(again.get(0).score()).isEqualTo(100);
        assertThat(template.queryForObject("SELECT count(*) FROM vacancy_match", Integer.class)).isEqualTo(1);
    }

    @Test
    void aMissingSalaryFollowsThePolicy() {
        Criterion pay = new Criterion("pay", "salary", "gte", json("{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}"),
                "prefer", 5, "penalize", "Pays well");
        profile("Main", List.of("all"), true, c("kw", "title", "contains_any", "[\"java\"]", "prefer", 5), pay);
        UUID id = vacancy("Java Developer", "ats-lever", null);

        VacancyMatch m = matchService.match(id).get(0);

        assertThat(m.score()).isZero();
        assertThat(m.reasons()).extracting(MatchReason::kind).containsExactly("prefer", "missing");
    }

    private com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void aSessionPicksUpAProfileSavedWhileItIsOpen() {
        SearchProfile p = profile("Main", List.of("all"), true, c("a", "title", "contains_any", "[\"java\"]", "prefer", 5));
        MatchService.MatchSession session = matchService.session();
        UUID first = vacancy("Java Developer", "ats-lever", null);
        assertThat(session.match(first).get(0).score()).isEqualTo(100);

        profiles.update(p.id(), new ProfileInput("Main", true, List.of("all"), null, Thresholds.defaults(),
                List.of(c("a", "title", "contains_any", "[\"cobol\"]", "prefer", 5))));
        UUID second = vacancy("Java Developer", "ats-lever", null);

        assertThat(session.match(second).get(0).score()).isZero();
    }

    private SearchProfile profileWithMaxAge(int maxAgeDays, Criterion... criteria) {
        return profiles.create(new ProfileInput("Aged", true, List.of("all"), null, new Thresholds(40, 70, maxAgeDays), List.of(criteria)));
    }

    private UUID vacancyPostedDaysAgo(String title, Integer daysAgo) {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), title, "Acme", "https://ex.com/" + UUID.randomUUID(), null, "Plain", NOW);
        if (daysAgo != null) {
            v.recordPostedAt(Instant.now().minus(daysAgo, java.time.temporal.ChronoUnit.DAYS));
        }
        vacancies.save(v.toState());
        vacancies.upsertSource(v.getId(), "djinni", UUID.randomUUID().toString(), "https://ex.com/x", NOW);
        return v.getId();
    }

    @Test
    void aPostingOlderThanTheProfilesMaxAgeIsFilteredOutForThatProfile() {
        profileWithMaxAge(30, c("kw", "title", "contains_any", "[\"java\"]", "prefer", 5));
        UUID old = vacancyPostedDaysAgo("Java Developer", 45);

        List<VacancyMatch> matches = matchService.match(old);

        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.hidden()).isTrue();
            assertThat(m.score()).isNull();
            assertThat(m.reasons().get(0).text()).startsWith("Filtered out: posted 45 days ago");
        });
        assertThat(storedMatchScore(old)).isNull();
    }

    @Test
    void aYoungOrUndatedPostingIsNotFilteredByAge() {
        profileWithMaxAge(30, c("kw", "title", "contains_any", "[\"java\"]", "prefer", 5));
        UUID young = vacancyPostedDaysAgo("Java Developer", 5);
        UUID undated = vacancyPostedDaysAgo("Java Engineer", null);

        assertThat(matchService.match(young)).singleElement().satisfies(m -> assertThat(m.hidden()).isFalse());
        assertThat(matchService.match(undated)).singleElement().satisfies(m -> {
            assertThat(m.hidden()).isFalse();
            assertThat(m.score()).isEqualTo(100);
        });
    }

    @Test
    void aPostedAgeRuleUsesTheStoredDate() {
        profileWithMaxAge(365, c("fresh", "postedAge", "lte", "7", "prefer", 4), c("kw", "title", "contains_any", "[\"java\"]", "prefer", 4));
        UUID fresh = vacancyPostedDaysAgo("Java Developer", 3);
        UUID stale = vacancyPostedDaysAgo("Java Engineer", 40);

        assertThat(matchService.match(fresh).get(0).score()).isEqualTo(100);
        assertThat(matchService.match(stale).get(0).score()).isEqualTo(50);
    }
}
