package com.vacancytracker.vacancy;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit SQL persistence for vacancies and their status history (ADR-003). */
@Repository
public class VacancyRepository {

    private static final String COLUMNS = """
            id, title, company, url, canonical_url, location, work_mode, description, salary_min, salary_max, currency, salary_period,
            status, priority, notes, contact, next_action_note, next_action_at, tags, user_edited_fields,
            first_seen_at, last_seen_at, is_new
            """;

    private final JdbcClient jdbc;

    public VacancyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(VacancyState s) {
        jdbc.sql("""
                INSERT INTO vacancy (id, title, company, url, canonical_url, location, work_mode, description, salary_min, salary_max,
                    currency, salary_period, status, priority, notes, contact, next_action_note, next_action_at,
                    tags, user_edited_fields, first_seen_at, last_seen_at, is_new, updated_at)
                VALUES (:id, :title, :company, :url, :canonicalUrl, :location, :workMode, :description, :salaryMin, :salaryMax,
                    :currency, :salaryPeriod, :status, :priority, :notes, :contact, :nextActionNote, :nextActionAt,
                    :tags, :editedFields, :firstSeenAt, :lastSeenAt, :isNew, now())
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title, company = EXCLUDED.company, url = EXCLUDED.url,
                    canonical_url = EXCLUDED.canonical_url, location = EXCLUDED.location,
                    work_mode = EXCLUDED.work_mode, description = EXCLUDED.description,
                    salary_min = EXCLUDED.salary_min, salary_max = EXCLUDED.salary_max,
                    currency = EXCLUDED.currency, salary_period = EXCLUDED.salary_period,
                    status = EXCLUDED.status, priority = EXCLUDED.priority, notes = EXCLUDED.notes,
                    contact = EXCLUDED.contact, next_action_note = EXCLUDED.next_action_note,
                    next_action_at = EXCLUDED.next_action_at, tags = EXCLUDED.tags,
                    user_edited_fields = EXCLUDED.user_edited_fields, last_seen_at = EXCLUDED.last_seen_at,
                    is_new = EXCLUDED.is_new, updated_at = now()
                """)
                .param("id", s.id())
                .param("title", s.title())
                .param("company", s.company())
                .param("url", s.url())
                .param("canonicalUrl", s.canonicalUrl())
                .param("location", s.location())
                .param("workMode", s.workMode())
                .param("description", s.description())
                .param("salaryMin", s.salaryMin())
                .param("salaryMax", s.salaryMax())
                .param("currency", s.currency())
                .param("salaryPeriod", s.salaryPeriod())
                .param("status", s.status().label())
                .param("priority", s.priority())
                .param("notes", s.notes())
                .param("contact", s.contact())
                .param("nextActionNote", s.nextActionNote())
                .param("nextActionAt", s.nextActionAt() == null ? null : OffsetDateTime.ofInstant(s.nextActionAt(), java.time.ZoneOffset.UTC))
                .param("tags", s.tags().toArray(String[]::new))
                .param("editedFields", s.userEditedFields().toArray(String[]::new))
                .param("firstSeenAt", OffsetDateTime.ofInstant(s.firstSeenAt(), java.time.ZoneOffset.UTC))
                .param("lastSeenAt", OffsetDateTime.ofInstant(s.lastSeenAt(), java.time.ZoneOffset.UTC))
                .param("isNew", s.isNew())
                .update();
    }

    public Optional<VacancyState> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vacancy WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Offset page ordered by most recently seen first; status filter is optional. */
    public List<VacancyState> findPage(String statusLabel, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vacancy"
                        + " WHERE CAST(:status AS TEXT) IS NULL OR status = :status"
                        + " ORDER BY last_seen_at DESC, id LIMIT :limit OFFSET :offset")
                .param("status", statusLabel)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> map(rs))
                .list();
    }

    public long count(String statusLabel) {
        return jdbc.sql("SELECT count(*) FROM vacancy WHERE CAST(:status AS TEXT) IS NULL OR status = :status")
                .param("status", statusLabel)
                .query(Long.class)
                .single();
    }

    public void appendStatusEvent(StatusEvent e) {
        jdbc.sql("INSERT INTO status_event (id, vacancy_id, from_status, to_status, at, note)"
                        + " VALUES (:id, :vacancyId, :from, :to, :at, :note)")
                .param("id", UUID.randomUUID())
                .param("vacancyId", e.vacancyId())
                .param("from", e.from() == null ? null : e.from().label())
                .param("to", e.to().label())
                .param("at", OffsetDateTime.ofInstant(e.at(), java.time.ZoneOffset.UTC))
                .param("note", e.note())
                .update();
    }

    public List<StatusEvent> findHistory(UUID vacancyId) {
        return jdbc.sql("SELECT vacancy_id, from_status, to_status, at, note FROM status_event"
                        + " WHERE vacancy_id = :id ORDER BY at, id")
                .param("id", vacancyId)
                .query((rs, n) -> new StatusEvent(
                        rs.getObject("vacancy_id", UUID.class),
                        rs.getString("from_status") == null ? null : VacancyStatus.fromLabel(rs.getString("from_status")),
                        VacancyStatus.fromLabel(rs.getString("to_status")),
                        rs.getObject("at", OffsetDateTime.class).toInstant(),
                        rs.getString("note")))
                .list();
    }

    private static VacancyState map(ResultSet rs) throws SQLException {
        return new VacancyState(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("company"),
                rs.getString("url"),
                rs.getString("canonical_url"),
                rs.getString("location"),
                rs.getString("work_mode"),
                rs.getString("description"),
                rs.getBigDecimal("salary_min"),
                rs.getBigDecimal("salary_max"),
                rs.getString("currency") == null ? null : rs.getString("currency").trim(),
                rs.getString("salary_period"),
                VacancyStatus.fromLabel(rs.getString("status")),
                (Integer) rs.getObject("priority"),
                rs.getString("notes"),
                rs.getString("contact"),
                rs.getString("next_action_note"),
                instant(rs, "next_action_at"),
                strings(rs.getArray("tags")),
                new LinkedHashSet<>(strings(rs.getArray("user_edited_fields"))),
                instant(rs, "first_seen_at"),
                instant(rs, "last_seen_at"),
                rs.getBoolean("is_new"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return Collections.emptyList();
        }
        return Arrays.asList((String[]) array.getArray());
    }
}
