package com.vacancytracker.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL for vacancy_match. Rows are replaced on every recalculation, so there is no score history. */
@Repository
class VacancyMatchRepository {

    private static final TypeReference<List<MatchReason>> REASONS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    VacancyMatchRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void upsert(VacancyMatch m) {
        jdbc.sql("INSERT INTO vacancy_match (vacancy_id, profile_id, profile_version, score, hidden, match_reasons, matched_at)"
                        + " VALUES (:vacancy, :profile, :version, :score, :hidden, CAST(:reasons AS JSONB), :at)"
                        + " ON CONFLICT (vacancy_id, profile_id) DO UPDATE SET profile_version = EXCLUDED.profile_version,"
                        + " score = EXCLUDED.score, hidden = EXCLUDED.hidden, match_reasons = EXCLUDED.match_reasons,"
                        + " matched_at = EXCLUDED.matched_at")
                .param("vacancy", m.vacancyId())
                .param("profile", m.profileId())
                .param("version", m.profileVersion())
                .param("score", m.score())
                .param("hidden", m.hidden())
                .param("reasons", json(m.reasons()))
                .param("at", OffsetDateTime.ofInstant(m.matchedAt(), ZoneOffset.UTC))
                .update();
    }

    /** Removes matches of profiles that no longer apply to the vacancy. */
    void deleteExcept(UUID vacancyId, Collection<UUID> keepProfileIds) {
        if (keepProfileIds.isEmpty()) {
            jdbc.sql("DELETE FROM vacancy_match WHERE vacancy_id = :v").param("v", vacancyId).update();
            return;
        }
        jdbc.sql("DELETE FROM vacancy_match WHERE vacancy_id = :v AND profile_id <> ALL (CAST(:keep AS UUID[]))")
                .param("v", vacancyId)
                .param("keep", keepProfileIds.stream().map(UUID::toString).toArray(String[]::new))
                .update();
    }

    List<VacancyMatch> findForVacancy(UUID vacancyId) {
        return jdbc.sql("SELECT vacancy_id, profile_id, profile_version, score, hidden, CAST(match_reasons AS TEXT) AS reasons,"
                        + " matched_at FROM vacancy_match WHERE vacancy_id = :v ORDER BY score DESC NULLS LAST, profile_id")
                .param("v", vacancyId)
                .query((rs, n) -> new VacancyMatch(rs.getObject("vacancy_id", UUID.class),
                        rs.getObject("profile_id", UUID.class), rs.getInt("profile_version"),
                        (Integer) rs.getObject("score"), rs.getBoolean("hidden"), read(rs.getString("reasons")),
                        rs.getObject("matched_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** The best score across enabled profiles that did not filter the vacancy out. */
    Optional<Integer> bestScore(UUID vacancyId) {
        return jdbc.sql("SELECT max(m.score) FROM vacancy_match m JOIN search_profile p ON p.id = m.profile_id"
                        + " WHERE m.vacancy_id = :v AND NOT m.hidden AND p.enabled")
                .param("v", vacancyId)
                .query((rs, n) -> (Integer) rs.getObject(1))
                .optional()
                .map(v -> v);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize match reasons", e);
        }
    }

    private List<MatchReason> read(String text) {
        try {
            return mapper.readValue(text, REASONS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored match reasons are not valid JSON", e);
        }
    }
}
