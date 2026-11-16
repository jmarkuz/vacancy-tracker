package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** What each sort key of the Pipeline table does to the order of the rows. */
@SpringBootTest
@AutoConfigureMockMvc
class VacancySortApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID add(String title, String status, String location, Integer min, Integer max, Integer score) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO vacancy (id, title, company, location, status, salary_min, salary_max, currency, match_score,"
                        + " first_seen_at, last_seen_at) VALUES (?, ?, 'Acme', ?, ?, ?, ?, 'EUR', ?, ?, ?)",
                id, title, location, status, min, max, score, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return id;
    }

    private List<String> titles(String sort) throws Exception {
        return titles(sort, "0", "50");
    }

    private List<String> titles(String sort, String page, String size) throws Exception {
        String body = mvc.perform(get("/api/vacancies").param("sort", sort).param("page", page).param("size", size))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].title");
    }

    @Test
    void textSortsIgnoreCaseWhateverTheDatabaseCollation() throws Exception {
        add("banana", "New", null, null, null, null);
        add("Cherry", "New", null, null, null, null);
        add("Apple", "New", null, null, null, null);

        assertThat(titles("title:asc")).containsExactly("Apple", "banana", "Cherry");
        assertThat(titles("title:desc")).containsExactly("Cherry", "banana", "Apple");
    }

    @Test
    void aMissingOrBlankPlaceComesLastInBothDirections() throws Exception {
        add("blank", "New", "  ", null, null, null);
        add("none", "New", null, null, null, null);
        add("berlin", "New", "Berlin", null, null, null);
        add("amsterdam", "New", "amsterdam", null, null, null);

        assertThat(titles("location:asc").subList(0, 2)).containsExactly("amsterdam", "berlin");
        assertThat(titles("location:desc").subList(0, 2)).containsExactly("berlin", "amsterdam");
    }

    @Test
    void salarySortsByTheHighestAmountStatedAndAFromOnlySalaryIsNotRankedAsNone() throws Exception {
        add("none", "New", null, null, null, null);
        add("from-only", "New", null, 90_000, null, null);
        add("range", "New", null, 50_000, 70_000, null);
        add("up-to", "New", null, null, 60_000, null);

        assertThat(titles("salaryMax:desc")).containsExactly("from-only", "range", "up-to", "none");
        assertThat(titles("salaryMax:asc")).containsExactly("up-to", "range", "from-only", "none");
    }

    @Test
    void statusSortsInPipelineOrderNotAlphabetically() throws Exception {
        add("hidden", "Hidden", null, null, null, null);
        add("applied", "Applied", null, null, null, null);
        add("offer", "Offer", null, null, null, null);
        add("new", "New", null, null, null, null);
        add("test", "Test task", null, null, null, null);

        assertThat(titles("status:asc")).containsExactly("new", "applied", "test", "offer", "hidden");
    }

    @Test
    void matchScoreSortsNumericallyWithUnscoredLast() throws Exception {
        add("low", "New", null, null, null, 9);
        add("unscored", "New", null, null, null, null);
        add("high", "New", null, null, null, 100);

        assertThat(titles("matchScore:desc")).containsExactly("high", "low", "unscored");
        assertThat(titles("matchScore:asc")).containsExactly("low", "high", "unscored");
    }

    @Test
    void equalKeysKeepTheirOrderAcrossPages() throws Exception {
        for (int i = 0; i < 7; i++) {
            add("same", "New", "Berlin", null, null, 50);
        }
        String body = mvc.perform(get("/api/vacancies").param("sort", "matchScore:desc").param("size", "7"))
                .andReturn().getResponse().getContentAsString();
        List<String> all = JsonPath.read(body, "$.items[*].id");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int page = 0; page < 3; page++) {
            String part = mvc.perform(get("/api/vacancies").param("sort", "matchScore:desc")
                            .param("page", String.valueOf(page)).param("size", "3"))
                    .andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(part, "$.items[*].id");
            seen.addAll(ids);
        }
        assertThat(seen).hasSize(7).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void anUnknownSortFieldIsRefused() throws Exception {
        mvc.perform(get("/api/vacancies").param("sort", "password:asc")).andExpect(status().isUnprocessableEntity());
    }
}
