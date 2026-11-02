package com.vacancytracker.application.resume;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for the resume table. At most one row is active (a partial unique index enforces it). */
@Repository
public class ResumeRepository {

    private static final String COLUMNS = "id, name, file_name, content_type, text, keywords, revision, uploaded_at, updated_at";

    private final JdbcClient jdbc;

    public ResumeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new resume and makes it the active one. */
    void insertActive(Resume r, byte[] original) {
        jdbc.sql("UPDATE resume SET active = FALSE WHERE active").update();
        jdbc.sql("INSERT INTO resume (id, name, file_name, content_type, original, text, keywords, revision, active,"
                        + " uploaded_at, updated_at) VALUES (:id, :name, :file, :type, :original, :text, CAST(:keywords AS TEXT[]),"
                        + " :revision, TRUE, :uploaded, :updated)")
                .param("id", r.id())
                .param("name", r.name())
                .param("file", r.fileName())
                .param("type", r.contentType())
                .param("original", original)
                .param("text", r.text())
                .param("keywords", r.keywords().toArray(String[]::new))
                .param("revision", r.revision())
                .param("uploaded", OffsetDateTime.ofInstant(r.uploadedAt(), ZoneOffset.UTC))
                .param("updated", OffsetDateTime.ofInstant(r.updatedAt(), ZoneOffset.UTC))
                .update();
    }

    void updateKeywords(UUID id, List<String> keywords, OffsetDateTime at) {
        jdbc.sql("UPDATE resume SET keywords = CAST(:keywords AS TEXT[]), revision = revision + 1, updated_at = :at WHERE id = :id")
                .param("keywords", keywords.toArray(String[]::new))
                .param("at", at)
                .param("id", id)
                .update();
    }

    public Optional<Resume> findActive() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM resume WHERE active").query(ResumeRepository::map).optional();
    }

    Optional<Original> findOriginal(UUID id) {
        return jdbc.sql("SELECT file_name, content_type, original FROM resume WHERE id = :id AND original IS NOT NULL")
                .param("id", id)
                .query((rs, n) -> new Original(rs.getString("file_name"), rs.getString("content_type"), rs.getBytes("original")))
                .optional();
    }

    void delete(UUID id) {
        jdbc.sql("DELETE FROM resume WHERE id = :id").param("id", id).update();
    }

    /** The uploaded file as it came. */
    public record Original(String fileName, String contentType, byte[] bytes) {
    }

    private static Resume map(ResultSet rs, int row) throws SQLException {
        return new Resume(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getString("text"), strings(rs.getArray("keywords")), rs.getInt("revision"),
                rs.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
