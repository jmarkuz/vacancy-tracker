package com.vacancytracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class BaselineSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatesCoreTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                String.class);

        assertThat(tables).contains("vacancy", "vacancy_source", "status_event", "saved_view", "flyway_schema_history");
    }

    @Test
    void searchVectorIsGeneratedFromTitleCompanyAndDescription() {
        jdbc.update("INSERT INTO vacancy (id, title, company, status, first_seen_at, last_seen_at, description)"
                + " VALUES (gen_random_uuid(), 'Platform Engineer', 'Northwind', 'New', now(), now(), 'Kubernetes')");

        Integer hits = jdbc.queryForObject(
                "SELECT count(*) FROM vacancy WHERE search_vector @@ to_tsquery('simple', 'kubernetes')", Integer.class);

        assertThat(hits).isEqualTo(1);
    }

    @Test
    void rejectsUnknownStatus() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO vacancy (id, title, company, status, first_seen_at, last_seen_at)"
                        + " VALUES (gen_random_uuid(), 'x', 'y', 'Bogus', now(), now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
