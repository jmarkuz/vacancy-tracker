package com.vacancytracker.collection.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.collection.normalize.NormalizedPosting;
import com.vacancytracker.collection.pipeline.IngestService;
import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.VacancyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rules are tested in their order: a later rule only applies when the earlier ones do not match (ADR-013). */
@SpringBootTest
class DeduplicatorTest extends AbstractIntegrationTest {

    @Autowired
    private Deduplicator deduplicator;

    @Autowired
    private IngestService ingest;

    @Autowired
    private VacancyRepository vacancies;

    @Autowired
    private JdbcTemplate template;

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE run, source_state, http_cache CASCADE");
    }

    private static NormalizedPosting p(String adapter, String ext, String canonical, String title, String company,
                                       String location) {
        return new NormalizedPosting(adapter, ext, canonical == null ? null : canonical, canonical, title, company,
                location, null, null, null, (BigDecimal) null, null, null, null, null);
    }

    @Test
    void canonicalUrlWinsOverEverything() {
        ingest.ingest(p("a", "1", "https://ex.com/job/1", "Java Developer", "Acme", "Berlin"), NOW);

        Optional<Deduplicator.Match> match = deduplicator.match(p("b", "zz", "https://ex.com/job/1", "Other", "Other", null));

        assertThat(match).isPresent();
        assertThat(match.get().rule()).isEqualTo(Deduplicator.Rule.CANONICAL_URL);
    }

    @Test
    void sameSourceExternalIdMatchesWhenUrlDiffers() {
        ingest.ingest(p("a", "42", "https://ex.com/job/42", "Java Developer", "Acme", "Berlin"), NOW);

        var match = deduplicator.match(p("a", "42", "https://ex.com/moved/42", "Java Developer", "Acme", "Berlin"));

        assertThat(match).isPresent();
        assertThat(match.get().rule()).isEqualTo(Deduplicator.Rule.SOURCE_ID);
    }

    @Test
    void sameExternalIdFromAnotherAdapterDoesNotMatch() {
        ingest.ingest(p("a", "42", "https://ex.com/job/42", "Java Developer", "Acme", "Berlin"), NOW);

        assertThat(deduplicator.match(p("b", "42", "https://other.example/42", "Unrelated", "Other", "Paris"))).isEmpty();
    }

    @Test
    void fuzzyKeyMatchesTypoInTitleWithinSameCompanyAndLocation() {
        ingest.ingest(p("a", null, null, "Data Engineer", "Acme", "Berlin"), NOW);

        var match = deduplicator.match(p("b", null, null, "Data Enginer", "acme", "Berlin"));

        assertThat(match).isPresent();
        assertThat(match.get().rule()).isEqualTo(Deduplicator.Rule.FUZZY_KEY);
    }

    @Test
    void differentRoleOrCompanyIsNotMatched() {
        ingest.ingest(p("a", null, null, "Data Engineer", "Acme", "Berlin"), NOW);

        assertThat(deduplicator.match(p("b", null, null, "Frontend Engineer", "Acme", "Berlin"))).isEmpty();
        assertThat(deduplicator.match(p("b", null, null, "Data Engineer", "Globex", "Berlin"))).isEmpty();
    }

    @Test
    void sameTitleInAnotherCityOrAnotherLevelOrVariantIsAnotherVacancy() {
        ingest.ingest(p("a", null, null, "Software Engineer", "Acme", "Berlin"), NOW);
        ingest.ingest(p("a", null, null, "Software Engineer I", "Acme", "Berlin"), NOW);
        ingest.ingest(p("a", null, null, "Backend Engineer (Java)", "Acme", "Berlin"), NOW);

        assertThat(deduplicator.match(p("b", null, null, "Software Engineer", "Acme", "London"))).isEmpty();
        assertThat(deduplicator.match(p("b", null, null, "Software Engineer II", "Acme", "Berlin"))).isEmpty();
        assertThat(deduplicator.match(p("b", null, null, "Backend Engineer (Go)", "Acme", "Berlin"))).isEmpty();
    }

    @Test
    void punctuationGenderSuffixAndPlaceDetailDoNotMakeADifferentVacancy() {
        ingest.ingest(p("a", null, null, "Data Engineer (m/f/d)", "Acme", "Berlin"), NOW);

        assertThat(deduplicator.match(p("b", null, null, "Data  Engineer", "Acme", "Berlin, Germany"))).isPresent();
        assertThat(deduplicator.match(p("b", null, null, "Data Engineer", "Acme", null))).isPresent();
    }

    @Test
    void ingestRecordsOneVacancyAndOneSourceRowPerAdapter() {
        ingest.ingest(p("a", "1", "https://ex.com/job/1", "Java Developer", "Acme", "Berlin"), NOW);
        ingest.ingest(p("b", "9", "https://ex.com/job/1", "Java Developer", "Acme", "Berlin"), NOW);

        assertThat(template.queryForObject("SELECT count(*) FROM vacancy", Integer.class)).isEqualTo(1);
        assertThat(template.queryForObject("SELECT count(*) FROM vacancy_source", Integer.class)).isEqualTo(2);
        assertThat(vacancies.findIdBySource("b", "9")).isPresent();
    }
}
