package com.vacancytracker.application.fit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for resume_fit. The vacancy text is read here too, as the Telegram digest does, to keep batches cheap. */
@Repository
class ResumeFitRepository {

    private static final TypeReference<List<ResumeFitScorer.Reason>> REASONS = new TypeReference<>() {
    };

    /** The vacancy text the score reads. */
    record VacancyText(UUID id, String title, String description) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    ResumeFitRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void upsert(ResumeFit f) {
        jdbc.sql("INSERT INTO resume_fit (vacancy_id, resume_id, resume_revision, score, matched, missing, reasons, computed_at)"
                        + " VALUES (:v, :r, :rev, :score, CAST(:matched AS TEXT[]), CAST(:missing AS TEXT[]), CAST(:reasons AS JSONB), :at)"
                        + " ON CONFLICT (vacancy_id) DO UPDATE SET resume_id = EXCLUDED.resume_id, resume_revision = EXCLUDED.resume_revision,"
                        + " score = EXCLUDED.score, matched = EXCLUDED.matched, missing = EXCLUDED.missing, reasons = EXCLUDED.reasons,"
                        + " computed_at = EXCLUDED.computed_at")
                .param("v", f.vacancyId())
                .param("r", f.resumeId())
                .param("rev", f.resumeRevision())
                .param("score", f.score())
                .param("matched", f.matched().toArray(String[]::new))
                .param("missing", f.missing().toArray(String[]::new))
                .param("reasons", json(f.reasons()))
                .param("at", OffsetDateTime.ofInstant(f.computedAt(), ZoneOffset.UTC))
                .update();
    }

    Optional<ResumeFit> find(UUID vacancyId) {
        return findAll(List.of(vacancyId)).stream().findFirst();
    }

    List<ResumeFit> findAll(Collection<UUID> vacancyIds) {
        if (vacancyIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT vacancy_id, resume_id, resume_revision, score, matched, missing, CAST(reasons AS TEXT) AS reasons,"
                        + " computed_at FROM resume_fit WHERE vacancy_id = ANY (CAST(:ids AS UUID[]))")
                .param("ids", vacancyIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, n) -> new ResumeFit(rs.getObject("vacancy_id", UUID.class), rs.getObject("resume_id", UUID.class),
                        rs.getInt("resume_revision"), rs.getInt("score"), strings(rs.getArray("matched")),
                        strings(rs.getArray("missing")), read(rs.getString("reasons")),
                        rs.getObject("computed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    Optional<VacancyText> vacancy(UUID id) {
        return jdbc.sql("SELECT id, title, description FROM vacancy WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new VacancyText(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description")))
                .optional();
    }

    /** Vacancies with no fit for this resume revision, oldest sighting first. */
    List<VacancyText> stale(UUID resumeId, int revision, int limit) {
        return jdbc.sql("SELECT v.id, v.title, v.description FROM vacancy v LEFT JOIN resume_fit f ON f.vacancy_id = v.id"
                        + " WHERE f.vacancy_id IS NULL OR f.resume_id <> :r OR f.resume_revision < :rev"
                        + " ORDER BY v.first_seen_at, v.id LIMIT :limit")
                .param("r", resumeId)
                .param("rev", revision)
                .param("limit", limit)
                .query((rs, n) -> new VacancyText(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description")))
                .list();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize fit reasons", e);
        }
    }

    private List<ResumeFitScorer.Reason> read(String text) {
        try {
            return mapper.readValue(text, REASONS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored fit reasons are not valid JSON", e);
        }
    }
}
