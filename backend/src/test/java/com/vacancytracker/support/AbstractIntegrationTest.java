package com.vacancytracker.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests (ADR-022). One PostgreSQL 16 container is started per test JVM
 * and shared by all classes: Spring caches application contexts across classes, so a per-class
 * container would leave cached contexts pointing at a stopped database. Flyway migrations run at
 * context startup.
 */
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    /** Each test starts with an empty database; the container and Spring context are shared. */
    @BeforeEach
    void truncateTables() {
        jdbc.execute("TRUNCATE vacancy, saved_view, search_profile CASCADE");
    }
}
