package org.eclipse.ecsp.sql.postgress.config;

import org.eclipse.ecsp.sql.authentication.DefaultPostgresDbCredentialsProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test class for {@link PostgresDbConfig}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { PostgresDbConfig.class, DefaultPostgresDbCredentialsProvider.class })
@TestPropertySource("/application-ssl-invalid-crt-test.properties")
@Testcontainers
@Disabled
class PostgresDbInvalidRootCrtTest {

    /** The default postgres db credentials provider. */
    @Autowired
    DefaultPostgresDbCredentialsProvider defaultPostgresDbCredentialsProvider;

    /** The data source. */
    @Autowired
    private DataSource dataSource;

    /** The postgresql container. */
    @Container
    static PostgreSQLContainer postgresqlContainer = new PostgreSQLContainer("postgres:15").withDatabaseName("test")
          .withUsername("root").withPassword("root");

    /**
     * Sets up postgres.
     */
    @BeforeAll
     public static void setUpPostgres() {
        // Container is automatically started by @Testcontainers annotation
        System.setProperty("DB_URL", postgresqlContainer.getJdbcUrl());
    }

    /**
     * Test connection.
     *
     * @throws SQLException the SQL exception
     */
    @Test
    void testConnection() throws SQLException {
        assertNull(dataSource.getConnection());
    }
}