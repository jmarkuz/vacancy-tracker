package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.RecalculationService.Progress;
import com.vacancytracker.matching.RecalculationService.State;
import com.vacancytracker.support.AbstractIntegrationTest;
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

@SpringBootTest
class RecalculationIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private RecalculationService recalculation;

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

    private UUID vacancy(String title) {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), title, "Acme", "https://ex.com/" + UUID.randomUUID(), null, "Plain", NOW);
        vacancies.save(v.toState());
        vacancies.upsertSource(v.getId(), "ats-lever", UUID.randomUUID().toString(), "https://ex.com/x", NOW);
        return v.getId();
    }

    private Progress awaitCompletion() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Progress p = recalculation.progress();
            if (p.state() == State.COMPLETED) {
                return p;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The recalculation did not finish");
    }

    @Test
    void recalculatesEveryVacancyAndNeverChangesAStatusTheUserSet() throws Exception {
        profiles.create(new ProfileInput("Main", true, List.of("all"), null, Thresholds.defaults(), List.of(
                new Criterion("kw", "title", "contains_any", mapper.readTree("[\"java\"]"), "prefer", 5, null, "Java"))));
        UUID good = vacancy("Java Developer");
        UUID poor = vacancy("Cobol Developer");
        UUID userSaved = vacancy("Cobol Analyst");
        vacancyService.changeStatus(userSaved, VacancyStatus.SAVED, null);
        UUID userHid = vacancy("Cobol Tester");
        vacancyService.changeStatus(userHid, VacancyStatus.HIDDEN, "no thanks");

        recalculation.start();
        Progress done = awaitCompletion();

        assertThat(done.total()).isEqualTo(4);
        assertThat(done.processed()).isEqualTo(4);
        assertThat(done.failed()).isZero();
        assertThat(template.queryForObject("SELECT count(*) FROM vacancy_match", Integer.class)).isEqualTo(4);
        assertThat(vacancyService.get(good).getStatus()).isEqualTo(VacancyStatus.NEW);
        assertThat(vacancyService.get(poor).getStatus()).isEqualTo(VacancyStatus.HIDDEN);
        assertThat(vacancyService.get(userSaved).getStatus()).isEqualTo(VacancyStatus.SAVED);
        assertThat(vacancyService.get(userHid).getStatus()).isEqualTo(VacancyStatus.HIDDEN);
        assertThat(vacancyService.history(userSaved)).hasSize(1);
        assertThat(template.queryForObject("SELECT match_score FROM vacancy WHERE id = ?", Integer.class, good)).isEqualTo(100);
    }

    @Test
    void aProfileChangeFollowedByARecalculationMovesTheScores() throws Exception {
        SearchProfile p = profiles.create(new ProfileInput("Main", true, List.of("all"), null, new Thresholds(0, 70, 60), List.of(
                new Criterion("kw", "title", "contains_any", mapper.readTree("[\"cobol\"]"), "prefer", 5, null, null))));
        UUID id = vacancy("Java Developer");
        recalculation.start();
        awaitCompletion();
        assertThat(template.queryForObject("SELECT match_score FROM vacancy WHERE id = ?", Integer.class, id)).isZero();

        profiles.update(p.id(), new ProfileInput("Main", true, List.of("all"), null, new Thresholds(0, 70, 60), List.of(
                new Criterion("kw", "title", "contains_any", mapper.readTree("[\"java\"]"), "prefer", 5, null, null))));
        recalculation.start();
        awaitCompletion();

        assertThat(template.queryForObject("SELECT match_score FROM vacancy WHERE id = ?", Integer.class, id)).isEqualTo(100);
        assertThat(template.queryForObject("SELECT profile_version FROM vacancy_match WHERE vacancy_id = ?", Integer.class, id)).isEqualTo(2);
    }
}
