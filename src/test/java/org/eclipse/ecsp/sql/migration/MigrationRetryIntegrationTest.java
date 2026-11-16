/*
 *
 *
 *   ******************************************************************************
 *
 *    Copyright (c) 2023-24 Harman International
 *
 *
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *
 *    you may not use this file except in compliance with the License.
 *
 *    You may obtain a copy of the License at
 *
 *
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *    Unless required by applicable law or agreed to in writing, software
 *
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *    See the License for the specific language governing permissions and
 *
 *    limitations under the License.
 *
 *
 *
 *    SPDX-License-Identifier: Apache-2.0
 *
 *    *******************************************************************************
 *
 *
 */

package org.eclipse.ecsp.sql.migration;

import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Retries a migration whose database connection is killed in the middle of a changeset, using real PostgreSQL,
 * Liquibase and PgJDBC exceptions. The failed attempt also leaves {@code DATABASECHANGELOGLOCK} locked, so the retry
 * relies on stale lock recovery as well.
 */
@Testcontainers
class MigrationRetryIntegrationTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-retry-test.xml";
    private static final String SCHEMA = "retry_tenant";
    private static final int CHANGESETS = 4;
    private static final int ATTEMPTS_NEEDED = 2;
    private static final long RETRY_DELAY_MS = 200L;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("orders_db")
            .withUsername("root")
            .withPassword("root");

    @Test
    void recoversWhenConnectionIsKilledInTheMiddleOfChangeset() throws SQLException {
        SqlDaoLiquibaseProperties properties = properties();
        MigrationDataSourceResolver resolver = new MigrationDataSourceResolver(properties, null);
        Set<String> failed;
        try (MigrationSession session = resolver.openSession()) {
            failed = runner(properties, resolver).migrateAllTenants(Map.of("tenant1", tenant()), Set.of(), session);
        }

        assertTrue(failed.isEmpty());
        assertEquals(ATTEMPTS_NEEDED, count(SCHEMA + ".attempts"));
        assertEquals(CHANGESETS, count(SCHEMA + ".databasechangelog"));
        assertFalse(changelogLocked());
    }

    @Test
    void failsWhenRetriesAreDisabled() {
        SqlDaoLiquibaseProperties properties = properties();
        properties.getRetry().setMaxAttempts(1);
        MigrationDataSourceResolver resolver = new MigrationDataSourceResolver(properties, null);
        TenantDatabaseProperties tenant = tenant();
        tenant.setSchema("no_retry_tenant");
        try (MigrationSession session = resolver.openSession()) {
            assertThrows(MigrationException.class,
                    () -> runner(properties, resolver).migrateAllTenants(Map.of("tenant2", tenant), Set.of(), session));
        }
    }

    private static SqlDaoLiquibaseProperties properties() {
        SqlDaoLiquibaseProperties properties = new SqlDaoLiquibaseProperties();
        properties.setChangeLog(CHANGELOG);
        properties.getRetry().setDelayMs(RETRY_DELAY_MS);
        AdminDataSourceProperties admin = new AdminDataSourceProperties();
        admin.setJdbcUrl(postgres.getJdbcUrl());
        admin.setUserName(postgres.getUsername());
        admin.setPassword(postgres.getPassword());
        properties.getAdmin().put("ordersdb", admin);
        return properties;
    }

    private static TenantMigrationRunner runner(SqlDaoLiquibaseProperties properties,
            MigrationDataSourceResolver resolver) {
        SchemaMigrationLock lock = new SchemaMigrationLock();
        LiquibaseMigrationService liquibase = new LiquibaseMigrationService(properties, new DefaultResourceLoader());
        MigrationRetryExecutor retry = new MigrationRetryExecutor(properties);
        return new TenantMigrationRunner(properties, resolver, lock, new SchemaProvisioningService(properties),
                liquibase, new InfrastructureMigrationRunner(properties, resolver, lock, liquibase, retry),
                mock(MigrationConfigurationValidator.class), retry, Map.of());
    }

    private static TenantDatabaseProperties tenant() {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(postgres.getJdbcUrl());
        tenant.setSchema(SCHEMA);
        return tenant;
    }

    private static int count(String table) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static boolean changelogLocked() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT locked FROM " + SCHEMA + ".databasechangeloglock")) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
