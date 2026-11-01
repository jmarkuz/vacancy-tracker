package com.vacancytracker.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.vacancytracker.support.AbstractIntegrationTest;
import com.vacancytracker.vacancy.VacancySearchService.Hit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class VacancySearchServiceTest extends AbstractIntegrationTest {

    @Autowired
    private VacancySearchService search;

    @Autowired
    private JdbcTemplate template;

    private UUID add(String title, String company, String description) {
        UUID id = UUID.randomUUID();
        template.update("INSERT INTO vacancy (id, title, company, description, status, first_seen_at, last_seen_at)"
                        + " VALUES (?, ?, ?, ?, 'New', ?, ?)", id, title, company, description,
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    @Test
    void aTitleMatchRanksAboveADescriptionMatch() {
        UUID inDescription = add("Platform Engineer", "Acme", "We use Kotlin and Postgres every day.");
        UUID inTitle = add("Kotlin Developer", "Beta", "Build services.");

        List<Hit> hits = search.search("kotlin", 10);

        assertThat(hits).extracting(Hit::id).containsExactly(inTitle, inDescription);
        assertThat(hits.get(0).rank()).isGreaterThan(hits.get(1).rank());
    }

    @Test
    void aCompanyMatchRanksAboveADescriptionMatch() {
        UUID description = add("Engineer", "Acme", "Worked with Globex before.");
        UUID company = add("Engineer", "Globex", "Build things.");

        assertThat(search.search("globex", 10)).extracting(Hit::id).containsExactly(company, description);
    }

    @Test
    void wordsArePrefixesAndAllMustMatch() {
        UUID both = add("Senior Kotlin Developer", "Acme", null);
        add("Senior Java Developer", "Acme", null);

        assertThat(search.search("sen kot", 10)).extracting(Hit::id).containsExactly(both);
    }

    @Test
    void theSnippetMarksTheMatchedWordWithoutHtml() {
        add("Engineer", "Acme", "Our stack is <b>Kotlin</b> on the server side with a lot of other long words around it.");

        Hit hit = search.search("kotlin", 10).get(0);

        assertThat(hit.snippet()).contains("[[Kotlin]]");
    }

    @Test
    void noMatchBlankAndSymbolOnlyTextGiveNothing() {
        add("Engineer", "Acme", "Plain.");

        assertThat(search.search("zzzzzz", 10)).isEmpty();
        assertThat(search.search("", 10)).isEmpty();
        assertThat(search.search("&|!", 10)).isEmpty();
    }

    @Test
    void theLimitIsAppliedAndCapped() {
        for (int i = 0; i < 60; i++) {
            add("Kotlin " + i, "Acme", null);
        }

        assertThat(search.search("kotlin", 5)).hasSize(5);
        assertThat(search.search("kotlin", 1000)).hasSize(VacancySearchService.MAX_LIMIT);
        assertThat(search.search("kotlin", 0)).hasSize(1);
    }

    @Test
    void hostileInputIsHarmless() {
        add("Engineer", "Acme", "Plain.");

        assertThat(search.search("'; DROP TABLE vacancy; --", 10)).isEmpty();
        assertThat(search.search("engineer", 10)).hasSize(1);
    }
}
