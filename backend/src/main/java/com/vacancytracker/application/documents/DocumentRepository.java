package com.vacancytracker.application.documents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for tailored_document. Rows are only ever inserted. */
@Repository
class DocumentRepository {

    private static final String COLUMNS = "id, vacancy_id, kind, version, content, origin, provider, model, created_at, snapshot_id";

    private final JdbcClient jdbc;

    DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the next version for the vacancy and kind; the unique key settles a race between two saves. */
    TailoredDocument insertNext(UUID vacancyId, String kind, String content, String origin, String provider, String model,
                                java.time.Instant at, UUID snapshotId) {
        UUID id = UUID.randomUUID();
        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM tailored_document WHERE vacancy_id = :v AND kind = :k")
                .param("v", vacancyId).param("k", kind).query(Integer.class).single();
        jdbc.sql("INSERT INTO tailored_document (" + COLUMNS + ") VALUES (:id, :v, :k, :version, :content, :origin, :provider, :model, :at, :snapshot)")
                .param("id", id)
                .param("v", vacancyId)
                .param("k", kind)
                .param("version", version)
                .param("content", content)
                .param("origin", origin)
                .param("provider", provider)
                .param("model", model)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("snapshot", snapshotId)
                .update();
        return new TailoredDocument(id, vacancyId, kind, version, content, origin, provider, model, at, snapshotId);
    }

    Optional<TailoredDocument> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tailored_document WHERE id = :id").param("id", id)
                .query(DocumentRepository::map).optional();
    }

    List<TailoredDocument> forVacancy(UUID vacancyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tailored_document WHERE vacancy_id = :v ORDER BY kind, version DESC")
                .param("v", vacancyId).query(DocumentRepository::map).list();
    }

    private static TailoredDocument map(ResultSet rs, int row) throws SQLException {
        return new TailoredDocument(rs.getObject("id", UUID.class), rs.getObject("vacancy_id", UUID.class), rs.getString("kind"),
                rs.getInt("version"), rs.getString("content"), rs.getString("origin"), rs.getString("provider"),
                rs.getString("model"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("snapshot_id", UUID.class));
    }
}
