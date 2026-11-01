package com.vacancytracker.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
}
