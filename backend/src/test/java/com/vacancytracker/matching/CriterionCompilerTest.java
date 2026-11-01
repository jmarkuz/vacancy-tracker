package com.vacancytracker.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.common.SqlFragment;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriterionCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CriterionCompiler compiler = new CriterionCompiler(mapper);

    private Criterion c(String json) throws Exception {
        return mapper.readValue(json, Criterion.class);
    }

    @Test
    void emptyCriteriaMatchEverything() {
        assertThat(compiler.compile(List.of())).isEqualTo(SqlFragment.ALL);
    }

    @Test
    void containsAnyBindsEveryValueAsParameter() throws Exception {
        SqlFragment f = compiler.compile(List.of(c(
                "{\"field\":\"title\",\"operator\":\"contains_any\",\"value\":[\"java\",\"kotlin\"]}")));

        assertThat(f.sql()).contains("title ILIKE :f0 ESCAPE", "title ILIKE :f1 ESCAPE");
        assertThat(f.sql()).doesNotContain("java").doesNotContain("kotlin");
        assertThat(f.params()).containsEntry("f0", "%java%").containsEntry("f1", "%kotlin%");
    }

    @Test
    void likeWildcardsInValuesAreEscaped() throws Exception {
        SqlFragment f = compiler.compile(List.of(c(
                "{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"x\"}"),
                c("{\"field\":\"title\",\"operator\":\"contains_any\",\"value\":[\"50%_off\"]}")));

        assertThat(f.params()).containsEntry("f1", "%50\\%\\_off%");
    }

    @Test
    void excludeNegatesTheSatisfiedPredicate() throws Exception {
        SqlFragment f = compiler.compile(List.of(c(
                "{\"field\":\"company\",\"operator\":\"equals\",\"value\":\"Acme\",\"mode\":\"exclude\"}")));

        assertThat(f.sql()).startsWith("(NOT COALESCE(");
    }

    @Test
    void salaryAndDateUseTypedParameters() throws Exception {
        SqlFragment f = compiler.compile(List.of(
                c("{\"field\":\"salary\",\"operator\":\"gte\",\"value\":60000}"),
                c("{\"field\":\"lastSeenAt\",\"operator\":\"gte\",\"value\":\"2026-10-01T00:00:00Z\"}")));

        assertThat(f.sql()).contains("COALESCE(salary_max, salary_min) >= :f0", "last_seen_at >= :f1");
        assertThat(f.params()).containsEntry("f0", new java.math.BigDecimal("60000"));
        assertThat(f.params().get("f1")).isInstanceOf(java.time.OffsetDateTime.class);
    }

    @Test
    void sourceFilterUsesExistsOnSourceTable() throws Exception {
        SqlFragment f = compiler.compile(List.of(c("{\"field\":\"source\",\"operator\":\"in\",\"value\":[\"arbeitnow\"]}")));
        assertThat(f.sql()).contains("EXISTS (SELECT 1 FROM vacancy_source s WHERE s.vacancy_id = vacancy.id");
    }

    @Test
    void rejectsUnknownFieldOperatorOrMode() throws Exception {
        assertThatThrownBy(() -> compiler.validate(List.of(c("{\"field\":\"country\",\"operator\":\"equals\",\"value\":\"DE\"}"))))
                .isInstanceOf(CriterionException.class).hasMessageContaining("not yet supported");
        assertThatThrownBy(() -> compiler.validate(List.of(c("{\"field\":\"priority\",\"operator\":\"contains_any\",\"value\":[1]}"))))
                .isInstanceOf(CriterionException.class).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> compiler.validate(List.of(c("{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"x\",\"mode\":\"prefer\"}"))))
                .isInstanceOf(CriterionException.class).hasMessageContaining("require and exclude");
    }

    @Test
    void rejectsBadValues() throws Exception {
        assertThatThrownBy(() -> compiler.validate(List.of(c("{\"field\":\"status\",\"operator\":\"equals\",\"value\":\"Nope\"}"))))
                .isInstanceOf(CriterionException.class).hasMessageContaining("Unknown status");
        assertThatThrownBy(() -> compiler.validate(List.of(c("{\"field\":\"title\",\"operator\":\"regex\",\"value\":\"([\"}"))))
                .isInstanceOf(CriterionException.class).hasMessageContaining("regular expression");
        assertThatThrownBy(() -> compiler.validate(List.of(c("{\"field\":\"priority\",\"operator\":\"between\",\"value\":[1]}"))))
                .isInstanceOf(CriterionException.class);
        assertThatThrownBy(() -> compiler.validate(List.of(c("{\"field\":\"title\",\"operator\":\"in\",\"value\":[]}"))))
                .isInstanceOf(CriterionException.class);
        assertThatThrownBy(() -> compiler.parse("not json")).isInstanceOf(CriterionException.class);
    }
}
