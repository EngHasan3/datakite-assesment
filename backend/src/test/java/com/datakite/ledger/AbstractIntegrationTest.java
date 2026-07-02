package com.datakite.ledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full application (real HTTP layer, interceptor, aspect,
 * pessimistic locking, Liquibase) against a real PostgreSQL container.
 *
 * Uses Testcontainers' "singleton container" pattern deliberately instead of
 * @Testcontainers/@Container: that JUnit 5 extension stops the container
 * after each test *class*, so a second subclass would start a fresh
 * container on a new mapped port while Spring's cached ApplicationContext
 * (reused across subclasses because the configuration looks identical)
 * still points at the old, now-dead port - every test after the first class
 * failed with "connection refused" until this was tracked down. Starting the
 * container once in a static initializer and never stopping it (Testcontainers'
 * Ryuk reaper cleans it up when the JVM exits) avoids that entirely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String baseUrl() {
        return "http://localhost:" + port + "/api/v1/ledger";
    }
}
