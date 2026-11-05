package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

/** V28 repairs the vacancies the card-link alert layout spoiled, and nothing else. It is run again here on crafted rows. */
@SpringBootTest
class CardLinkRepairMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID insert(String title, String company, String location, String edited) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vacancy (id, title, company, location, user_edited_fields, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, string_to_array(?, ','), now(), now())""", id, title, company, location, edited);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT title, company, location FROM vacancy WHERE id = ?", id);
    }

    private void migrate() throws Exception {
        String sql = StreamUtils.copyToString(new ClassPathResource("db/migration/V28__fix_card_link_company_location.sql").getInputStream(),
                StandardCharsets.UTF_8);
        jdbc.execute(sql);
    }

    @Test
    void aSpoiledRowGetsItsTitleCompanyAndPlaceBack() throws Exception {
        UUID id = insert("Java Developer (43917) CoolPeople Technology · Prague", "Java Developer (43917)", "CoolPeople Technology · Prague", "");
        UUID mode = insert("Senior Java Developer Barclays · Prague, Czechia (Змішаний формат роботи) x", "Senior Java Developer",
                "Barclays · Prague, Czechia (Змішаний формат роботи)", "");

        migrate();

        assertThat(row(id)).containsEntry("title", "Java Developer (43917)").containsEntry("company", "CoolPeople Technology")
                .containsEntry("location", "Prague");
        // The title here is not company + location exactly, so it is not recognised and stays as it is.
        assertThat(row(mode)).containsEntry("title", "Senior Java Developer Barclays · Prague, Czechia (Змішаний формат роботи) x");
    }

    @Test
    void aLocationWithAnotherSeparatorInItKeepsTheRest() throws Exception {
        UUID id = insert("Backend Engineer Acme Pay · Co · Berlin", "Backend Engineer", "Acme Pay · Co · Berlin", "");

        migrate();

        assertThat(row(id)).containsEntry("title", "Backend Engineer").containsEntry("company", "Acme Pay").containsEntry("location", "Co · Berlin");
    }

    @Test
    void goodRowsAndRowsTheOwnerEditedAreLeftAlone() throws Exception {
        UUID good = insert("Backend Engineer", "Acme", "Berlin", "");
        UUID noSeparator = insert("Backend Engineer Berlin", "Backend Engineer", "Berlin", "");
        UUID noLocation = insert("Backend Engineer", "Backend Engineer", null, "");
        UUID edited = insert("Java Developer CoolPeople · Prague", "Java Developer", "CoolPeople · Prague", "company");
        UUID startsWithSeparator = insert("Java Developer  · Prague", "Java Developer", " · Prague", "");

        migrate();

        assertThat(row(good)).containsEntry("title", "Backend Engineer").containsEntry("company", "Acme").containsEntry("location", "Berlin");
        assertThat(row(noSeparator)).containsEntry("company", "Backend Engineer");
        assertThat(row(noLocation)).containsEntry("company", "Backend Engineer");
        assertThat(row(edited)).containsEntry("title", "Java Developer CoolPeople · Prague").containsEntry("company", "Java Developer");
        assertThat(row(startsWithSeparator)).containsEntry("company", "Java Developer");
    }

    @Test
    void runningItAgainChangesNothingMore() throws Exception {
        UUID id = insert("Java Developer (1) Acme · Prague", "Java Developer (1)", "Acme · Prague", "");

        migrate();
        migrate();

        assertThat(row(id)).containsEntry("title", "Java Developer (1)").containsEntry("company", "Acme").containsEntry("location", "Prague");
        assertThat(jdbc.queryForList("SELECT id FROM vacancy WHERE search_vector @@ to_tsquery('simple', 'acme')", UUID.class)).isEqualTo(List.of(id));
    }
}
