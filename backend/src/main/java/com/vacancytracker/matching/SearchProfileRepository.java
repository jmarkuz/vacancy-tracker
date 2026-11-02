package com.vacancytracker.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for profiles, their criteria and version snapshots (ADR-003). JSON is handled here. */
@Repository
class SearchProfileRepository {

    record VersionInfo(int version, Instant savedAt) {
    }

    record Snapshot(ProfileInput content, Instant savedAt) {
    }

    private static final TypeReference<ProfileInput> CONTENT = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    SearchProfileRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insert(UUID id, ProfileInput in, int version, Instant at) {
        jdbc.sql("INSERT INTO search_profile (id, name, enabled, sources, thresholds, schedule_override, version, updated_at)"
                        + " VALUES (:id, :name, :enabled, :sources, CAST(:thresholds AS JSONB), :schedule, :version, :at)")
                .param("id", id)
                .param("name", in.name())
                .param("enabled", in.enabled())
                .param("sources", in.sources().toArray(String[]::new))
                .param("thresholds", json(in.thresholds()))
                .param("schedule", in.scheduleOverride())
                .param("version", version)
                .param("at", utc(at))
                .update();
        replaceCriteria(id, in.criteria());
    }

    void update(UUID id, ProfileInput in, int version, Instant at) {
        jdbc.sql("UPDATE search_profile SET name = :name, enabled = :enabled, sources = :sources,"
                        + " thresholds = CAST(:thresholds AS JSONB), schedule_override = :schedule, version = :version,"
                        + " updated_at = :at WHERE id = :id")
                .param("id", id)
                .param("name", in.name())
                .param("enabled", in.enabled())
                .param("sources", in.sources().toArray(String[]::new))
                .param("thresholds", json(in.thresholds()))
                .param("schedule", in.scheduleOverride())
                .param("version", version)
                .param("at", utc(at))
                .update();
        replaceCriteria(id, in.criteria());
    }

    private void replaceCriteria(UUID profileId, List<Criterion> criteria) {
        jdbc.sql("DELETE FROM criterion WHERE profile_id = :id").param("id", profileId).update();
        for (int i = 0; i < criteria.size(); i++) {
            Criterion c = criteria.get(i);
            jdbc.sql("INSERT INTO criterion (profile_id, id, position, field, operator, value, mode, weight,"
                            + " missing_value_policy, label) VALUES (:profile, :id, :position, :field, :operator,"
                            + " CAST(:value AS JSONB), :mode, :weight, :policy, :label)")
                    .param("profile", profileId)
                    .param("id", c.id())
                    .param("position", i)
                    .param("field", c.field())
                    .param("operator", c.operator())
                    .param("value", c.value() == null ? null : json(c.value()))
                    .param("mode", c.mode())
                    .param("weight", c.weight())
                    .param("policy", c.missingValuePolicy())
                    .param("label", c.label())
                    .update();
        }
    }

    void insertVersion(UUID profileId, int version, ProfileInput content, Instant at) {
        jdbc.sql("INSERT INTO search_profile_version (profile_id, version, snapshot, saved_at)"
                        + " VALUES (:id, :version, CAST(:snapshot AS JSONB), :at)")
                .param("id", profileId)
                .param("version", version)
                .param("snapshot", json(content))
                .param("at", utc(at))
                .update();
    }

    Optional<SearchProfile> find(UUID id) {
        return jdbc.sql("SELECT id, name, enabled, sources, CAST(thresholds AS TEXT) AS thresholds, schedule_override,"
                        + " version, updated_at FROM search_profile WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> toProfile(rs))
                .optional();
    }

    List<SearchProfile> findAll() {
        return jdbc.sql("SELECT id, name, enabled, sources, CAST(thresholds AS TEXT) AS thresholds, schedule_override,"
                        + " version, updated_at FROM search_profile ORDER BY name")
                .query((rs, n) -> toProfile(rs))
                .list();
    }

    List<VersionInfo> versions(UUID id) {
        return jdbc.sql("SELECT version, saved_at FROM search_profile_version WHERE profile_id = :id ORDER BY version DESC")
                .param("id", id)
                .query((rs, n) -> new VersionInfo(rs.getInt("version"), rs.getObject("saved_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    Optional<Snapshot> snapshot(UUID id, int version) {
        return jdbc.sql("SELECT CAST(snapshot AS TEXT) AS snapshot, saved_at FROM search_profile_version"
                        + " WHERE profile_id = :id AND version = :version")
                .param("id", id)
                .param("version", version)
                .query((rs, n) -> new Snapshot(read(rs.getString("snapshot")), rs.getObject("saved_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM search_profile WHERE id = :id").param("id", id).update() > 0;
    }

    private SearchProfile toProfile(java.sql.ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Array sources = rs.getArray("sources");
        return new SearchProfile(id, rs.getString("name"), rs.getBoolean("enabled"),
                sources == null ? List.of() : List.of((String[]) sources.getArray()),
                rs.getString("schedule_override"), readThresholds(rs.getString("thresholds")), criteria(id),
                rs.getInt("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private List<Criterion> criteria(UUID profileId) {
        return jdbc.sql("SELECT id, field, operator, CAST(value AS TEXT) AS value, mode, weight, missing_value_policy, label"
                        + " FROM criterion WHERE profile_id = :id ORDER BY position")
                .param("id", profileId)
                .query((rs, n) -> new Criterion(rs.getString("id"), rs.getString("field"), rs.getString("operator"),
                        node(rs.getString("value")), rs.getString("mode"), rs.getInt("weight"),
                        rs.getString("missing_value_policy"), rs.getString("label")))
                .list();
    }

    private Thresholds readThresholds(String text) {
        try {
            return mapper.readValue(text, Thresholds.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored thresholds are not valid JSON", e);
        }
    }

    private ProfileInput read(String text) {
        try {
            return mapper.readValue(text, CONTENT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored profile snapshot is not valid JSON", e);
        }
    }

    private JsonNode node(String text) {
        try {
            return text == null ? null : mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored criterion value is not valid JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize profile data", e);
        }
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
