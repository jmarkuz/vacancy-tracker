package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.ProfilePreviewService.Preview;
import com.vacancytracker.matching.ProfilePreviewService.Sample;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ProfilePreviewServiceTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private ProfilePreviewService previews;

    @Autowired
    private MatchService matchService;

    @Autowired
    private SearchProfileService profiles;

    @Autowired
    private VacancyRepository vacancies;

    @Autowired
    private CompanyListService companyLists;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate template;

    private Criterion c(String id, String field, String op, String value, String mode, int weight) {
        try {
            return new Criterion(id, field, op, mapper.readTree(value), mode, weight, null, "Label " + id);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ProfileInput draft(List<String> sources, int inboxMin, Criterion... criteria) {
        return new ProfileInput("Draft", true, sources, null, new Thresholds(inboxMin, 70, 60), List.of(criteria));
    }

    private UUID vacancy(String title, String adapter) {
        Vacancy v = Vacancy.discovered(UUID.randomUUID(), title, "Acme", "https://ex.com/" + UUID.randomUUID(), null, "Plain", NOW);
        vacancies.save(v.toState());
        vacancies.upsertSource(v.getId(), adapter, UUID.randomUUID().toString(), "https://ex.com/x", NOW);
        return v.getId();
    }

    private int count(String sql) {
        return template.queryForObject(sql, Integer.class);
    }

    @Test
    void countsWhatWouldMatchAndLandInTheInboxAndPicksSamples() {
        vacancy("Senior Java Engineer", "ats-lever");   // 100
        vacancy("Java Developer", "ats-lever");         // 80
        vacancy("Cobol Developer", "ats-lever");        // 0
        vacancy("Java Intern", "ats-lever");            // filtered
        ProfileInput draft = draft(List.of("all"), 40,
                c("java", "title", "contains_any", "[\"java\"]", "prefer", 8),
                c("senior", "title", "contains_any", "[\"senior\"]", "prefer", 2),
                c("ban", "title", "contains_any", "[\"intern\"]", "exclude", 5));

        Preview p = previews.preview(draft);

        assertThat(p.total()).isEqualTo(4);
        assertThat(p.applicable()).isEqualTo(4);
        assertThat(p.matched()).isEqualTo(3);
        assertThat(p.inbox()).isEqualTo(2);
        assertThat(p.filteredOut()).isEqualTo(1);
        assertThat(p.samples()).extracting(Sample::kind, Sample::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple("inbox", "Senior Java Engineer"),
                org.assertj.core.groups.Tuple.tuple("inbox", "Java Developer"),
                org.assertj.core.groups.Tuple.tuple("below-threshold", "Cobol Developer"),
                org.assertj.core.groups.Tuple.tuple("filtered-out", "Java Intern"));
        Sample best = p.samples().get(0);
        assertThat(best.score()).isEqualTo(100);
        assertThat(best.reasons()).extracting(MatchReason::criterionId).containsExactly("java", "senior");
        Sample filtered = p.samples().get(3);
        assertThat(filtered.score()).isNull();
        assertThat(filtered.reasons()).extracting(MatchReason::kind).contains("exclude");
    }

    @Test
    void onlyVacanciesFromTheProfilesSourcesAreApplicable() {
        vacancy("Java Developer", "ats-lever");
        vacancy("Java Architect", "arbeitnow");
        vacancy("Java Tester", "arbeitnow");

        Preview p = previews.preview(draft(List.of("arbeitnow"), 40, c("java", "title", "contains_any", "[\"java\"]", "prefer", 5)));

        assertThat(p.total()).isEqualTo(3);
        assertThat(p.applicable()).isEqualTo(2);
        assertThat(p.matched()).isEqualTo(2);
    }

    @Test
    void thePreviewWritesNothing() {
        UUID id = vacancy("Cobol Developer", "ats-lever");
        ProfileInput strict = draft(List.of("all"), 90, c("java", "title", "contains_any", "[\"java\"]", "prefer", 5));
        int matchesBefore = count("SELECT count(*) FROM vacancy_match");
        int eventsBefore = count("SELECT count(*) FROM status_event");

        previews.preview(strict);

        assertThat(count("SELECT count(*) FROM vacancy_match")).isEqualTo(matchesBefore).isZero();
        assertThat(count("SELECT count(*) FROM status_event")).isEqualTo(eventsBefore);
        assertThat(count("SELECT count(*) FROM search_profile")).isZero();
        assertThat(template.queryForObject("SELECT status FROM vacancy WHERE id = ?", String.class, id)).isEqualTo("New");
        assertThat(template.queryForObject("SELECT match_score FROM vacancy WHERE id = ?", Integer.class, id)).isNull();
    }

    @Test
    void theProfilesOwnBlocklistIsAppliedWhenItsIdIsGiven() {
        vacancy("Java Developer", "arbeitnow");
        ProfileInput input = draft(List.of("all"), 0, c("java", "title", "contains_any", "[\"java\"]", "prefer", 5));
        SearchProfile saved = profiles.create(input);
        companyLists.add("Acme", "blocklist", saved.id());

        assertThat(previews.preview(input).filteredOut()).as("a new profile has no lists of its own").isZero();
        assertThat(previews.preview(input, saved.id()).filteredOut()).isEqualTo(1);
    }

    @Test
    void theCountsEqualWhatTheRealMatchingStores() {
        Random random = new Random(5);
        String[] tokens = {"java", "kotlin", "senior", "intern", "remote", "cobol"};
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            StringBuilder title = new StringBuilder("Role " + i);
            for (String t : tokens) {
                if (random.nextInt(3) == 0) {
                    title.append(' ').append(t);
                }
            }
            ids.add(vacancy(title.toString(), random.nextBoolean() ? "ats-lever" : "arbeitnow"));
        }
        ProfileInput input = draft(List.of("ats-lever"), 50,
                c("java", "title", "contains_any", "[\"java\",\"kotlin\"]", "prefer", 6),
                c("senior", "title", "contains_any", "[\"senior\"]", "prefer", 3),
                c("remote", "title", "contains_any", "[\"remote\"]", "avoid", 4),
                c("ban", "title", "contains_any", "[\"intern\"]", "exclude", 5));
        Preview preview = previews.preview(input);

        SearchProfile saved = profiles.create(input);
        ids.forEach(matchService::match);

        String where = "FROM vacancy_match WHERE profile_id = '" + saved.id() + "'";
        assertThat(preview.applicable()).isEqualTo(count("SELECT count(*) " + where));
        assertThat(preview.filteredOut()).isEqualTo(count("SELECT count(*) " + where + " AND hidden"));
        assertThat(preview.matched()).isEqualTo(count("SELECT count(*) " + where + " AND NOT hidden"));
        assertThat(preview.inbox()).isEqualTo(count("SELECT count(*) " + where + " AND NOT hidden AND score >= 50"));
        assertThat(preview.applicable()).isPositive();
        assertThat(preview.filteredOut()).isPositive();
    }

    @Test
    void readsEveryBatchWhenThereAreManyVacancies() {
        template.execute("INSERT INTO vacancy (id, title, company, status, first_seen_at, last_seen_at)"
                + " SELECT gen_random_uuid(), (CASE WHEN g % 3 = 0 THEN 'Java ' ELSE 'Cobol ' END) || g, 'Acme', 'New', now(), now()"
                + " FROM generate_series(1, 1200) g");

        Preview p = previews.preview(draft(List.of("all"), 40, c("java", "title", "contains_any", "[\"java\"]", "prefer", 5)));

        assertThat(p.total()).isEqualTo(1200);
        assertThat(p.applicable()).isEqualTo(1200);
        assertThat(p.matched()).isEqualTo(1200);
        assertThat(p.inbox()).isEqualTo(400);
    }

    @Test
    void anEmptyDatabaseGivesZeros() {
        Preview p = previews.preview(draft(List.of("all"), 40));

        assertThat(p.total()).isZero();
        assertThat(p.matched()).isZero();
        assertThat(p.samples()).isEmpty();
    }

    @Test
    void anInvalidDraftIsRejectedWithItsProblems() {
        ProfileInput bad = draft(List.of("all"), 40, c("x", "salary", "regex", "\"a\"", "prefer", 5));

        assertThatThrownBy(() -> previews.preview(bad)).isInstanceOf(ProfileValidationException.class);
    }
}
