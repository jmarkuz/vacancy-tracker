package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.StatusEvent;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancyStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The automatic inbox rule (ADR-026): hide below the threshold, restore when it qualifies, never override the user. */
@SpringBootTest
class InboxRuleTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private MatchService matchService;

    @Autowired
    private SearchProfileService profiles;

    @Autowired
    private VacancyRepository vacancies;

    @Autowired
    private VacancyService vacancyService;

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
            return new Criterion(id, field, op, mapper.readTree(value), mode, weight, null, null);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private SearchProfile profile(String name, int inboxMin, Criterion... criteria) {
        return profiles.create(new ProfileInput(name, true, List.of("all"), null, new Thresholds(inboxMin, 70, 60), List.of(criteria)));
    }

    private UUID vacancy(String title) {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), title, "Acme", "https://ex.com/" + UUID.randomUUID(), null, "Plain", NOW);
        vacancies.save(v.toState());
        vacancies.upsertSource(v.getId(), "ats-lever", UUID.randomUUID().toString(), "https://ex.com/x", NOW);
        return v.getId();
    }

    private VacancyStatus statusOf(UUID id) {
        return vacancyService.get(id).getStatus();
    }

    private List<StatusEvent> history(UUID id) {
        return vacancyService.history(id);
    }

    private Criterion java() {
        return c("kw", "title", "contains_any", "[\"java\"]", "prefer", 5);
    }

    @Test
    void aNewVacancyBelowTheThresholdIsHiddenWithAnAutomaticEvent() {
        profile("Main", 40, java());
        UUID id = vacancy("Cobol Developer");

        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.HIDDEN);
        assertThat(history(id)).hasSize(1);
        assertThat(history(id).get(0).note()).startsWith("auto-hidden");
        assertThat(vacancyService.get(id).isNew()).isTrue();
    }

    @Test
    void aVacancyTheUserAddedIsNotHiddenWhenASourceLaterListsIt() {
        profile("Main", 40, java());
        UUID id = vacancyService.create("Cobol Developer", "Acme", "https://ex.com/cobol", "Plain").getId();
        vacancies.upsertSource(id, "ats-lever", "cobol-1", "https://ex.com/cobol", NOW);

        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.NEW);
        assertThat(history(id)).extracting(StatusEvent::note).containsExactly(VacancyService.ADDED_BY_HAND_NOTE);
    }

    @Test
    void aVacancyAtOrAboveTheThresholdStaysNew() {
        profile("Main", 100, java());
        UUID id = vacancy("Java Developer");

        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.NEW);
        assertThat(history(id)).isEmpty();
    }

    @Test
    void aVacancyThatAProfileFiltersOutIsHidden() {
        profile("Strict", 0, c("ban", "title", "contains_any", "[\"intern\"]", "exclude", 5));
        UUID id = vacancy("Java Intern");

        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.HIDDEN);
    }

    @Test
    void theThresholdIsPerProfile() {
        profile("Demanding", 90, c("a", "title", "contains_any", "[\"java\"]", "prefer", 8),
                c("b", "title", "contains_any", "[\"senior\"]", "prefer", 2));
        UUID id = vacancy("Java Developer");   // 80 of 100

        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.HIDDEN);
    }

    @Test
    void oneAcceptingProfileIsEnough() {
        profile("Rejects", 50, c("a", "title", "contains_any", "[\"cobol\"]", "prefer", 5));
        profile("Accepts", 50, java());
        UUID id = vacancy("Java Developer");

        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.NEW);
    }

    @Test
    void anAutomaticallyHiddenVacancyIsRestoredWhenItQualifiesAgain() {
        SearchProfile p = profile("Main", 40, c("kw", "title", "contains_any", "[\"cobol\"]", "prefer", 5));
        UUID id = vacancy("Java Developer");
        matchService.match(id);
        assertThat(statusOf(id)).isEqualTo(VacancyStatus.HIDDEN);

        profiles.update(p.id(), new ProfileInput("Main", true, List.of("all"), null, Thresholds.defaults(), List.of(java())));
        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.NEW);
        assertThat(history(id)).extracting(StatusEvent::note).satisfiesExactly(
                n -> assertThat(n).startsWith("auto-hidden"), n -> assertThat(n).startsWith("auto-restored"));
    }

    @Test
    void withNoApplicableProfileNothingIsFilteredAndAnAutoHiddenOneComesBack() {
        SearchProfile p = profile("Main", 40, java());
        UUID id = vacancy("Cobol Developer");
        matchService.match(id);
        assertThat(statusOf(id)).isEqualTo(VacancyStatus.HIDDEN);

        profiles.update(p.id(), new ProfileInput("Main", false, List.of("all"), null, Thresholds.defaults(), p.criteria()));
        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.NEW);
    }

    // ---- the user always wins ----

    @Test
    void aStatusTheUserSetIsNeverChanged() {
        profile("Main", 40, java());
        UUID saved = vacancy("Cobol Developer");
        vacancyService.changeStatus(saved, VacancyStatus.SAVED, null);
        UUID applied = vacancy("Cobol Analyst");
        vacancyService.changeStatus(applied, VacancyStatus.APPLIED, null);

        matchService.match(saved);
        matchService.match(applied);

        assertThat(statusOf(saved)).isEqualTo(VacancyStatus.SAVED);
        assertThat(statusOf(applied)).isEqualTo(VacancyStatus.APPLIED);
        assertThat(history(saved)).hasSize(1);
    }

    @Test
    void aVacancyTheUserHidAndLaterQualifiesStaysHidden() {
        SearchProfile p = profile("Main", 40, c("kw", "title", "contains_any", "[\"cobol\"]", "prefer", 5));
        UUID id = vacancy("Java Developer");
        vacancyService.changeStatus(id, VacancyStatus.HIDDEN, "not interested");

        profiles.update(p.id(), new ProfileInput("Main", true, List.of("all"), null, Thresholds.defaults(), List.of(java())));
        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.HIDDEN);
    }

    @Test
    void aVacancyTheUserRestoredIsNotHiddenAgain() {
        profile("Main", 40, java());
        UUID id = vacancy("Cobol Developer");
        matchService.match(id);
        assertThat(statusOf(id)).isEqualTo(VacancyStatus.HIDDEN);

        vacancyService.changeStatus(id, VacancyStatus.NEW, null);
        matchService.match(id);

        assertThat(statusOf(id)).isEqualTo(VacancyStatus.NEW);
    }
}
