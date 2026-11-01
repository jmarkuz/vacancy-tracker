package com.vacancytracker.collection.pipeline;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Runs and per-source statistics (RunSourceStat in the brief). */
@Repository
public class RunRepository {

    /** Counters for one adapter in one run. */
    public static final class SourceStats {
        public int fetched;
        public int newCount;
        public int updated;
        public int duplicates;
        public int errors;
    }

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID start(String trigger, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO run (id, trigger, started_at, status) VALUES (:id, :trigger, :at, 'running')")
                .param("id", id)
                .param("trigger", trigger)
                .param("at", utc(at))
                .update();
        return id;
    }

    public void recordStat(UUID runId, String adapter, SourceStats stats, long durationMs, String error) {
        jdbc.sql("INSERT INTO run_source_stat (id, run_id, adapter, fetched, new_count, updated, duplicates, errors,"
                        + " duration_ms, error_message) VALUES (:id, :run, :adapter, :fetched, :new, :updated,"
                        + " :duplicates, :errors, :duration, :error)")
                .param("id", UUID.randomUUID())
                .param("run", runId)
                .param("adapter", adapter)
                .param("fetched", stats.fetched)
                .param("new", stats.newCount)
                .param("updated", stats.updated)
                .param("duplicates", stats.duplicates)
                .param("errors", stats.errors)
                .param("duration", durationMs)
                .param("error", error)
                .update();
    }

    public record RunRow(UUID id, String trigger, String status, Instant startedAt, Instant finishedAt) {
    }

    public record StatRow(String adapter, int fetched, int newCount, int updated, int duplicates, int errors,
                          long durationMs, String errorMessage) {
    }

    public Optional<RunRow> findRun(UUID id) {
        return jdbc.sql("SELECT id, trigger, status, started_at, finished_at FROM run WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new RunRow(rs.getObject("id", UUID.class), rs.getString("trigger"),
                        rs.getString("status"), instant(rs.getObject("started_at", OffsetDateTime.class)),
                        instant(rs.getObject("finished_at", OffsetDateTime.class))))
                .optional();
    }

    public List<StatRow> findStats(UUID runId) {
        return jdbc.sql("SELECT adapter, fetched, new_count, updated, duplicates, errors, duration_ms, error_message"
                        + " FROM run_source_stat WHERE run_id = :id ORDER BY adapter")
                .param("id", runId)
                .query((rs, n) -> new StatRow(rs.getString("adapter"), rs.getInt("fetched"), rs.getInt("new_count"),
                        rs.getInt("updated"), rs.getInt("duplicates"), rs.getInt("errors"), rs.getLong("duration_ms"),
                        rs.getString("error_message")))
                .list();
    }

    /** Only a running run can be finished, so a late finish cannot overwrite an interrupted run. */
    public void finish(UUID runId, String status, Instant at) {
        jdbc.sql("UPDATE run SET status = :status, finished_at = :at WHERE id = :id AND status = 'running'")
                .param("status", status)
                .param("at", utc(at))
                .param("id", runId)
                .update();
    }

    public Optional<RunRow> findLatestFinished() {
        return jdbc.sql("SELECT id, trigger, status, started_at, finished_at FROM run"
                        + " WHERE status <> 'running' ORDER BY finished_at DESC NULLS LAST LIMIT 1")
                .query((rs, n) -> new RunRow(rs.getObject("id", UUID.class), rs.getString("trigger"),
                        rs.getString("status"), instant(rs.getObject("started_at", OffsetDateTime.class)),
                        instant(rs.getObject("finished_at", OffsetDateTime.class))))
                .optional();
    }

    public String status(UUID runId) {
        return jdbc.sql("SELECT status FROM run WHERE id = :id").param("id", runId).query(String.class).single();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
