package com.vacancytracker.application.packaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for application_package. */
@Repository
class PackageRepository {

    private static final String COLUMNS = "id, vacancy_id, resume_document_id, cover_letter_document_id, apply_url, note, status,"
            + " created_at, submitted_at, resume_file_name";

    /** The package's own copy of the uploaded resume; pdf is its PDF when the upload was not a PDF itself (T-097). */
    record ResumeCopy(String fileName, String contentType, byte[] bytes, byte[] pdf) {

        ResumeCopy(String fileName, String contentType, byte[] bytes) {
            this(fileName, contentType, bytes, null);
        }
    }

    private final JdbcClient jdbc;

    PackageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** copy is null when the package carries a tailored resume version instead. */
    void insert(ApplicationPackage p, ResumeCopy copy) {
        jdbc.sql("INSERT INTO application_package (" + COLUMNS + ", resume_content_type, resume_bytes, resume_pdf)"
                        + " VALUES (:id, :v, :r, :c, :url, :note, :status, :at, NULL, :file, :type, :bytes, :pdf)")
                .param("id", p.id())
                .param("v", p.vacancyId())
                .param("r", p.resumeDocumentId())
                .param("c", p.coverLetterDocumentId())
                .param("url", p.applyUrl())
                .param("note", p.note())
                .param("status", p.status())
                .param("at", OffsetDateTime.ofInstant(p.createdAt(), ZoneOffset.UTC))
                .param("file", copy == null ? null : copy.fileName())
                .param("type", copy == null ? null : copy.contentType())
                .param("bytes", copy == null ? null : copy.bytes())
                .param("pdf", copy == null ? null : copy.pdf())
                .update();
    }

    Optional<ResumeCopy> resumeCopy(UUID id) {
        return jdbc.sql("SELECT resume_file_name, resume_content_type, resume_bytes, resume_pdf FROM application_package"
                        + " WHERE id = :id AND resume_bytes IS NOT NULL")
                .param("id", id)
                .query((rs, n) -> new ResumeCopy(rs.getString("resume_file_name"), rs.getString("resume_content_type"),
                        rs.getBytes("resume_bytes"), rs.getBytes("resume_pdf")))
                .optional();
    }

    /** Marks it submitted once; answers false when it already was. */
    boolean markSubmitted(UUID id, Instant at) {
        return jdbc.sql("UPDATE application_package SET status = 'submitted', submitted_at = :at WHERE id = :id AND status = 'prepared'")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("id", id)
                .update() == 1;
    }

    Optional<ApplicationPackage> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM application_package WHERE id = :id").param("id", id)
                .query(PackageRepository::map).optional();
    }

    List<ApplicationPackage> forVacancy(UUID vacancyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM application_package WHERE vacancy_id = :v ORDER BY created_at DESC")
                .param("v", vacancyId).query(PackageRepository::map).list();
    }

    private static ApplicationPackage map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime submitted = rs.getObject("submitted_at", OffsetDateTime.class);
        return new ApplicationPackage(rs.getObject("id", UUID.class), rs.getObject("vacancy_id", UUID.class),
                rs.getObject("resume_document_id", UUID.class), rs.getObject("cover_letter_document_id", UUID.class),
                rs.getString("apply_url"), rs.getString("note"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), submitted == null ? null : submitted.toInstant(),
                rs.getString("resume_file_name"));
    }
}
