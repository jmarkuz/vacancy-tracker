package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SearchProfileServiceTest extends AbstractIntegrationTest {

    @Autowired
    private SearchProfileService service;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate template;

    @BeforeEach
    void clean() {
        template.execute("TRUNCATE search_profile CASCADE");
    }

    private Criterion c(String id, String field, String op, String value, String mode, int weight) throws Exception {
        return new Criterion(id, field, op, value == null ? null : mapper.readTree(value), mode, weight, null, "Label " + id);
    }

    private ProfileInput input(String name, List<Criterion> criteria) {
        return new ProfileInput(name, true, List.of("all"), null, new Thresholds(40, 70, 60), criteria);
    }

    @Test
    void createStoresVersionOneWithOrderedCriteriaAndJsonValues() throws Exception {
        SearchProfile created = service.create(input("Remote", List.of(
                c("second", "title", "contains_any", "[\"a\"]", "require", 5),
                c("first", "salary", "gte", "{\"amount\":50000,\"currency\":\"EUR\",\"period\":\"year\"}", "prefer", 6))));

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.criteria()).extracting(Criterion::id).containsExactly("second", "first");
        assertThat(created.criteria().get(1).value().get("currency").asText()).isEqualTo("EUR");
        assertThat(created.criteria().get(0).missingValuePolicy()).isEqualTo("ignore");
        assertThat(service.versions(created.id())).extracting(SearchProfileService.VersionSummary::version).containsExactly(1);
    }

    @Test
    void everySaveCreatesANewVersionAndOldOnesStayReadable() throws Exception {
        SearchProfile v1 = service.create(input("Remote", List.of(c("kw", "title", "contains_any", "[\"java\"]", "prefer", 5))));

        SearchProfile v2 = service.update(v1.id(), input("Remote EU", List.of(
                c("kw", "title", "contains_any", "[\"kotlin\"]", "prefer", 9),
                c("mode", "workMode", "in", "[\"remote\"]", "prefer", 4))));

        assertThat(v2.version()).isEqualTo(2);
        assertThat(service.versions(v1.id())).extracting(SearchProfileService.VersionSummary::version).containsExactly(2, 1);

        SearchProfile old = service.version(v1.id(), 1);
        assertThat(old.name()).isEqualTo("Remote");
        assertThat(old.criteria()).hasSize(1);
        assertThat(old.criteria().get(0).value().get(0).asText()).isEqualTo("java");
        assertThat(old.criteria().get(0).weight()).isEqualTo(5);

        SearchProfile current = service.get(v1.id());
        assertThat(current.name()).isEqualTo("Remote EU");
        assertThat(current.criteria()).hasSize(2);
    }

    @Test
    void restoreCopiesAnOldVersionIntoANewOne() throws Exception {
        SearchProfile v1 = service.create(input("Remote", List.of(c("kw", "title", "contains_any", "[\"java\"]", "prefer", 5))));
        service.update(v1.id(), input("Changed", List.of()));

        SearchProfile restored = service.restore(v1.id(), 1);

        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.name()).isEqualTo("Remote");
        assertThat(restored.criteria()).hasSize(1);
        assertThat(service.version(v1.id(), 2).name()).isEqualTo("Changed");
    }

    @Test
    void invalidSaveChangesNothing() throws Exception {
        SearchProfile v1 = service.create(input("Remote", List.of()));

        assertThatThrownBy(() -> service.update(v1.id(), input("Remote", List.of(
                c("x", "salary", "regex", "\"a\"", "prefer", 3)))))
                .isInstanceOf(ProfileValidationException.class);

        assertThat(service.get(v1.id()).version()).isEqualTo(1);
        assertThat(service.versions(v1.id())).hasSize(1);
    }

    @Test
    void unknownProfileOrVersionIsNotFoundAndDeleteRemovesHistory() throws Exception {
        SearchProfile created = service.create(input("Remote", List.of()));

        assertThatThrownBy(() -> service.version(created.id(), 9)).isInstanceOf(NotFoundException.class);
        service.delete(created.id());

        assertThatThrownBy(() -> service.get(created.id())).isInstanceOf(NotFoundException.class);
        assertThat(template.queryForObject("SELECT count(*) FROM search_profile_version", Integer.class)).isZero();
        assertThat(template.queryForObject("SELECT count(*) FROM criterion", Integer.class)).isZero();
    }
}
