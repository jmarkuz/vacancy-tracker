package com.vacancytracker.application.analysis;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for the analysis caches: vacancy_checklist, document_match and goal_match. JSON is kept as text here. */
@Repository
class AnalysisRepository {

    /** A stored checklist and the hash of the vacancy text it was built from. */
    record StoredChecklist(String textHash, String checklistHash, String json) {
    }

    /** A stored goal rating and the hash of the vacancy text it was made for. */
    record StoredGoal(String textHash, String json) {
    }

    private final JdbcClient jdbc;

    AnalysisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredChecklist> checklist(UUID vacancyId) {
        return jdbc.sql("SELECT text_hash, checklist_hash, CAST(checklist AS TEXT) AS checklist FROM vacancy_checklist WHERE vacancy_id = :v")
                .param("v", vacancyId)
                .query((rs, n) -> new StoredChecklist(rs.getString("text_hash"), rs.getString("checklist_hash"), rs.getString("checklist")))
                .optional();
    }

    /** Replaces the vacancy's checklist; matches made against an older checklist simply stop being found. */
    void saveChecklist(UUID vacancyId, String textHash, String checklistHash, String json, String provider, String model, Instant at) {
        jdbc.sql("INSERT INTO vacancy_checklist (vacancy_id, text_hash, checklist_hash, checklist, provider, model, created_at)"
                        + " VALUES (:v, :t, :c, CAST(:json AS JSONB), :p, :m, :at) ON CONFLICT (vacancy_id) DO UPDATE SET"
                        + " text_hash = EXCLUDED.text_hash, checklist_hash = EXCLUDED.checklist_hash, checklist = EXCLUDED.checklist,"
                        + " provider = EXCLUDED.provider, model = EXCLUDED.model, created_at = EXCLUDED.created_at")
                .param("v", vacancyId).param("t", textHash).param("c", checklistHash).param("json", json)
                .param("p", provider).param("m", model).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    Optional<String> match(UUID vacancyId, String checklistHash, String textHash) {
        return jdbc.sql("SELECT CAST(items AS TEXT) FROM document_match WHERE vacancy_id = :v AND checklist_hash = :c AND text_hash = :t")
                .param("v", vacancyId).param("c", checklistHash).param("t", textHash).query(String.class).optional();
    }

    void saveMatch(UUID vacancyId, String checklistHash, String textHash, String json, Instant at) {
        jdbc.sql("INSERT INTO document_match (vacancy_id, checklist_hash, text_hash, items, created_at)"
                        + " VALUES (:v, :c, :t, CAST(:json AS JSONB), :at) ON CONFLICT DO NOTHING")
                .param("v", vacancyId).param("c", checklistHash).param("t", textHash).param("json", json)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    Optional<StoredGoal> goal(UUID vacancyId, String goalHash) {
        return jdbc.sql("SELECT text_hash, CAST(result AS TEXT) AS result FROM goal_match WHERE vacancy_id = :v AND goal_hash = :g")
                .param("v", vacancyId).param("g", goalHash)
                .query((rs, n) -> new StoredGoal(rs.getString("text_hash"), rs.getString("result"))).optional();
    }

    void saveGoal(UUID vacancyId, String goalHash, String textHash, String json, Instant at) {
        jdbc.sql("INSERT INTO goal_match (vacancy_id, goal_hash, text_hash, result, created_at) VALUES (:v, :g, :t, CAST(:json AS JSONB), :at)"
                        + " ON CONFLICT (vacancy_id, goal_hash) DO UPDATE SET text_hash = EXCLUDED.text_hash, result = EXCLUDED.result,"
                        + " created_at = EXCLUDED.created_at")
                .param("v", vacancyId).param("g", goalHash).param("t", textHash).param("json", json)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }
}
