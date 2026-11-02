package com.vacancytracker.application.documents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.application.preset.Preset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for generation_snapshot. Rows are only ever inserted. */
@Repository
class SnapshotRepository {

    private static final TypeReference<List<Preset.Weight>> WEIGHTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    SnapshotRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insert(GenerationSnapshot s) {
        jdbc.sql("INSERT INTO generation_snapshot (id, vacancy_id, kind, preset_id, preset_name, guardrail, main_goal, weights, prompt,"
                        + " base_draft, base_resume, base_resume_id, created_at) VALUES (:id, :v, :kind, :preset, :presetName, :guardrail,"
                        + " :goal, CAST(:weights AS JSONB), :prompt, :base, :baseResume, :baseResumeId, :at)")
                .param("id", s.id())
                .param("v", s.vacancyId())
                .param("kind", s.kind())
                .param("preset", s.presetId())
                .param("presetName", s.presetName())
                .param("guardrail", s.guardrail())
                .param("goal", s.mainGoal())
                .param("weights", json(s.weights()))
                .param("prompt", s.prompt())
                .param("base", s.baseDraft())
                .param("baseResume", s.baseResume())
                .param("baseResumeId", s.baseResumeId())
                .param("at", OffsetDateTime.ofInstant(s.createdAt(), ZoneOffset.UTC))
                .update();
    }

    Optional<GenerationSnapshot> find(UUID id) {
        return jdbc.sql("SELECT id, vacancy_id, kind, preset_id, preset_name, guardrail, main_goal, CAST(weights AS TEXT) AS weights,"
                        + " prompt, base_draft, base_resume, base_resume_id, created_at FROM generation_snapshot WHERE id = :id")
                .param("id", id).query(this::map).optional();
    }

    private GenerationSnapshot map(ResultSet rs, int row) throws SQLException {
        return new GenerationSnapshot(rs.getObject("id", UUID.class), rs.getObject("vacancy_id", UUID.class), rs.getString("kind"),
                rs.getObject("preset_id", UUID.class), rs.getString("preset_name"), rs.getString("guardrail"), rs.getString("main_goal"),
                weights(rs.getString("weights")), rs.getString("prompt"), rs.getString("base_draft"), rs.getString("base_resume"),
                rs.getObject("base_resume_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private List<Preset.Weight> weights(String text) {
        try {
            return List.copyOf(mapper.readValue(text, WEIGHTS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored snapshot weights are not valid JSON", e);
        }
    }

    private String json(List<Preset.Weight> weights) {
        try {
            return mapper.writeValueAsString(weights);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize snapshot weights", e);
        }
    }
}
