package com.vacancytracker.application.preset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Explicit SQL for ai_preset. At most one row is the default (a partial unique index enforces it). */
@Repository
class PresetRepository {

    private static final TypeReference<List<Preset.Weight>> WEIGHTS = new TypeReference<>() {
    };
    private static final String COLUMNS = "id, name, is_default, main_goal, CAST(weights AS TEXT) AS weights, resume_prompt,"
            + " cover_letter_prompt, base_resume_id, base_cover_letter, base_cover_letter_file_name, created_at, updated_at";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    PresetRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    int count() {
        return jdbc.sql("SELECT count(*) FROM ai_preset").query(Integer.class).single();
    }

    void insert(Preset p) {
        jdbc.sql("INSERT INTO ai_preset (id, name, is_default, main_goal, weights, resume_prompt, cover_letter_prompt,"
                        + " base_resume_id, base_cover_letter, base_cover_letter_file_name, created_at, updated_at)"
                        + " VALUES (:id, :name, :def, :goal, CAST(:weights AS JSONB), :rp, :cp, :resume, :letter, :letterFile, :at, :updated)")
                .param("id", p.id())
                .param("name", p.name())
                .param("def", p.isDefault())
                .param("goal", p.mainGoal())
                .param("weights", json(p.weights()))
                .param("rp", p.resumePrompt())
                .param("cp", p.coverLetterPrompt())
                .param("resume", p.baseResumeId())
                .param("letter", p.baseCoverLetter())
                .param("letterFile", p.baseCoverLetterFileName())
                .param("at", utc(p.createdAt()))
                .param("updated", utc(p.updatedAt()))
                .update();
    }

    /** Inserts unless a preset with the same name or a second default would result (two first requests at once). */
    void insertIfAbsent(Preset p) {
        jdbc.sql("INSERT INTO ai_preset (id, name, is_default, main_goal, weights, resume_prompt, cover_letter_prompt,"
                        + " base_resume_id, base_cover_letter, base_cover_letter_file_name, created_at, updated_at)"
                        + " VALUES (:id, :name, :def, :goal, CAST(:weights AS JSONB), :rp, :cp, :resume, :letter, :letterFile, :at, :at)"
                        + " ON CONFLICT DO NOTHING")
                .param("id", p.id())
                .param("name", p.name())
                .param("def", p.isDefault())
                .param("goal", p.mainGoal())
                .param("weights", json(p.weights()))
                .param("rp", p.resumePrompt())
                .param("cp", p.coverLetterPrompt())
                .param("resume", p.baseResumeId())
                .param("letter", p.baseCoverLetter())
                .param("letterFile", p.baseCoverLetterFileName())
                .param("at", utc(p.createdAt()))
                .update();
    }

    /** Writes every editable field; the default flag is changed only by {@link #makeDefault}. */
    void update(Preset p) {
        jdbc.sql("UPDATE ai_preset SET name = :name, main_goal = :goal, weights = CAST(:weights AS JSONB), resume_prompt = :rp,"
                        + " cover_letter_prompt = :cp, base_resume_id = :resume, base_cover_letter = :letter,"
                        + " base_cover_letter_file_name = :letterFile, updated_at = :updated WHERE id = :id")
                .param("id", p.id())
                .param("name", p.name())
                .param("goal", p.mainGoal())
                .param("weights", json(p.weights()))
                .param("rp", p.resumePrompt())
                .param("cp", p.coverLetterPrompt())
                .param("resume", p.baseResumeId())
                .param("letter", p.baseCoverLetter())
                .param("letterFile", p.baseCoverLetterFileName())
                .param("updated", utc(p.updatedAt()))
                .update();
    }

    void makeDefault(UUID id) {
        jdbc.sql("UPDATE ai_preset SET is_default = FALSE WHERE is_default AND id <> :id").param("id", id).update();
        jdbc.sql("UPDATE ai_preset SET is_default = TRUE WHERE id = :id").param("id", id).update();
    }

    void setBaseResume(UUID id, UUID resumeId) {
        jdbc.sql("UPDATE ai_preset SET base_resume_id = :resume WHERE id = :id AND base_resume_id IS DISTINCT FROM :resume")
                .param("resume", resumeId).param("id", id).update();
    }

    void delete(UUID id) {
        jdbc.sql("DELETE FROM ai_preset WHERE id = :id").param("id", id).update();
    }

    Optional<Preset> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ai_preset WHERE id = :id").param("id", id).query(this::map).optional();
    }

    Optional<Preset> findByName(String name) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ai_preset WHERE lower(name) = lower(:name)").param("name", name)
                .query(this::map).optional();
    }

    Optional<Preset> findDefault() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ai_preset WHERE is_default").query(this::map).optional();
    }

    /** The default first, then by name. */
    List<Preset> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ai_preset ORDER BY is_default DESC, lower(name), created_at").query(this::map).list();
    }

    private Preset map(ResultSet rs, int row) throws SQLException {
        return new Preset(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("is_default"),
                rs.getString("main_goal"), weights(rs.getString("weights")), rs.getString("resume_prompt"),
                rs.getString("cover_letter_prompt"), rs.getObject("base_resume_id", UUID.class), rs.getString("base_cover_letter"),
                rs.getString("base_cover_letter_file_name"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private List<Preset.Weight> weights(String text) {
        try {
            return List.copyOf(mapper.readValue(text, WEIGHTS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored preset weights are not valid JSON", e);
        }
    }

    private String json(List<Preset.Weight> weights) {
        try {
            return mapper.writeValueAsString(weights);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize preset weights", e);
        }
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
