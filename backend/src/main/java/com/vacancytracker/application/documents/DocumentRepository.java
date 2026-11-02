package com.vacancytracker.application.documents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final String COLUMNS = "id, vacancy_id, kind, version, content, origin, provider, model, created_at, snapshot_id, pdf_pages,"
            + " CAST(fact_findings AS TEXT) AS fact_findings, facts_confirmed";

    private static final TypeReference<List<FactCheck.Finding>> FINDINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    DocumentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserts the next version for the vacancy and kind; the unique key settles a race between two saves. */
    TailoredDocument insertNext(UUID vacancyId, String kind, String content, String origin, String provider, String model,
                                java.time.Instant at, UUID snapshotId, ResumePdf.Rendered pdf, List<FactCheck.Finding> findings,
                                boolean confirmed) {
        UUID id = UUID.randomUUID();
        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM tailored_document WHERE vacancy_id = :v AND kind = :k")
                .param("v", vacancyId).param("k", kind).query(Integer.class).single();
        jdbc.sql("INSERT INTO tailored_document (id, vacancy_id, kind, version, content, origin, provider, model, created_at, snapshot_id,"
                        + " pdf_pages, pdf, fact_findings, facts_confirmed) VALUES (:id, :v, :k, :version, :content, :origin, :provider,"
                        + " :model, :at, :snapshot, :pages, :pdf, CAST(:findings AS JSONB), :confirmed)")
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
                .param("pages", pdf == null ? null : pdf.pages())
                .param("pdf", pdf == null ? null : pdf.bytes())
                .param("findings", findings == null ? null : json(findings))
                .param("confirmed", confirmed)
                .update();
        return new TailoredDocument(id, vacancyId, kind, version, content, origin, provider, model, at, snapshotId,
                pdf == null ? null : pdf.pages(), findings, confirmed);
    }

    Optional<byte[]> pdf(UUID id) {
        return jdbc.sql("SELECT pdf FROM tailored_document WHERE id = :id AND pdf IS NOT NULL").param("id", id).query(byte[].class).optional();
    }

    Optional<TailoredDocument> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tailored_document WHERE id = :id").param("id", id)
                .query(this::map).optional();
    }

    List<TailoredDocument> forVacancy(UUID vacancyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tailored_document WHERE vacancy_id = :v ORDER BY kind, version DESC")
                .param("v", vacancyId).query(this::map).list();
    }

    private String json(List<FactCheck.Finding> findings) {
        try {
            return mapper.writeValueAsString(findings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the fact check", e);
        }
    }

    private List<FactCheck.Finding> findings(String json) {
        try {
            return json == null ? null : mapper.readValue(json, FINDINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored fact check is not valid JSON", e);
        }
    }

    private TailoredDocument map(ResultSet rs, int row) throws SQLException {
        return new TailoredDocument(rs.getObject("id", UUID.class), rs.getObject("vacancy_id", UUID.class), rs.getString("kind"),
                rs.getInt("version"), rs.getString("content"), rs.getString("origin"), rs.getString("provider"),
                rs.getString("model"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("snapshot_id", UUID.class), rs.getObject("pdf_pages", Integer.class), findings(rs.getString("fact_findings")),
                rs.getBoolean("facts_confirmed"));
    }
}
