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
            + " created_at, submitted_at";

    private final JdbcClient jdbc;

    PackageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(ApplicationPackage p) {
        jdbc.sql("INSERT INTO application_package (" + COLUMNS + ") VALUES (:id, :v, :r, :c, :url, :note, :status, :at, NULL)")
                .param("id", p.id())
                .param("v", p.vacancyId())
                .param("r", p.resumeDocumentId())
                .param("c", p.coverLetterDocumentId())
                .param("url", p.applyUrl())
                .param("note", p.note())
                .param("status", p.status())
                .param("at", OffsetDateTime.ofInstant(p.createdAt(), ZoneOffset.UTC))
                .update();
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
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), submitted == null ? null : submitted.toInstant());
    }
}
