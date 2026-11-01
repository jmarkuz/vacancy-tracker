package com.vacancytracker.matching;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Raw rows of saved_view; JSON handling lives in {@link SavedViewService}. */
@Repository
class SavedViewRepository {

    record Row(UUID id, String name, String criteriaJson, String sort, List<String> columns) {
    }

    private final JdbcClient jdbc;

    SavedViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> findAll() {
        return jdbc.sql("SELECT id, name, CAST(criteria AS TEXT) AS criteria, sort, visible_columns FROM saved_view ORDER BY name")
                .query((rs, n) -> map(rs))
                .list();
    }

    Optional<Row> findById(UUID id) {
        return jdbc.sql("SELECT id, name, CAST(criteria AS TEXT) AS criteria, sort, visible_columns FROM saved_view WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    void insert(Row row) {
        jdbc.sql("INSERT INTO saved_view (id, name, criteria, sort, visible_columns, created_at, updated_at)"
                        + " VALUES (:id, :name, CAST(:criteria AS JSONB), :sort, :columns, now(), now())")
                .param("id", row.id())
                .param("name", row.name())
                .param("criteria", row.criteriaJson())
                .param("sort", row.sort())
                .param("columns", row.columns().toArray(String[]::new))
                .update();
    }

    int update(Row row) {
        return jdbc.sql("UPDATE saved_view SET name = :name, criteria = CAST(:criteria AS JSONB), sort = :sort,"
                        + " visible_columns = :columns, updated_at = now() WHERE id = :id")
                .param("id", row.id())
                .param("name", row.name())
                .param("criteria", row.criteriaJson())
                .param("sort", row.sort())
                .param("columns", row.columns().toArray(String[]::new))
                .update();
    }

    int delete(UUID id) {
        return jdbc.sql("DELETE FROM saved_view WHERE id = :id").param("id", id).update();
    }

    private static Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array cols = rs.getArray("visible_columns");
        List<String> columns = cols == null ? List.of() : List.of((String[]) cols.getArray());
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("criteria"),
                rs.getString("sort"), columns);
    }
}
