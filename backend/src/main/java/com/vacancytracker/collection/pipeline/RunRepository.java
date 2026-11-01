package com.vacancytracker.collection.pipeline;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    public void finish(UUID runId, String status, Instant at) {
        jdbc.sql("UPDATE run SET status = :status, finished_at = :at WHERE id = :id")
                .param("status", status)
                .param("at", utc(at))
                .param("id", runId)
                .update();
    }

    public String status(UUID runId) {
        return jdbc.sql("SELECT status FROM run WHERE id = :id").param("id", runId).query(String.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
