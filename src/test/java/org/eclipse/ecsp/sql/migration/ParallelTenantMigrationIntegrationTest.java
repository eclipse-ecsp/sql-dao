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

import org.eclipse.ecsp.sql.migration.MigrationDataSourceResolver.MigrationSession;
import org.eclipse.ecsp.sql.migration.SqlDaoLiquibaseProperties.AdminDataSourceProperties;
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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Migrates several tenant schemas of one database in parallel through a shared admin pool, using the real advisory
 * lock, schema provisioning and Liquibase.
 */
@Testcontainers
class ParallelTenantMigrationIntegrationTest {

    private static final int TENANTS = 6;
    private static final int PARALLELISM = 3;
    private static final String CHANGELOG = "db/changelog/db.changelog-parallel-test.xml";
    private static final int CHANGESETS = 2;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("orders_db")
            .withUsername("root")
            .withPassword("root");

    @Test
    void migratesTenantSchemasConcurrentlyThroughOneAdminAlias() throws SQLException {
        SqlDaoLiquibaseProperties properties = new SqlDaoLiquibaseProperties();
        properties.setChangeLog(CHANGELOG);
        properties.setParallelism(PARALLELISM);
        properties.getAdmin().put("ordersdb", admin());
        Map<String, TenantDatabaseProperties> tenants = new HashMap<>();
        for (int i = 1; i <= TENANTS; i++) {
            tenants.put("tenant" + i, tenant("tenant_" + i));
        }

        Set<String> failed;
        MigrationDataSourceResolver resolver = new MigrationDataSourceResolver(properties, null);
        try (MigrationSession session = resolver.openSession()) {
            failed = runner(properties, resolver).migrateAllTenants(tenants, Set.of(), session);
        }

        assertTrue(failed.isEmpty());
        List<Timestamp[]> runs = new ArrayList<>();
        for (int i = 1; i <= TENANTS; i++) {
            assertEquals(CHANGESETS, count("tenant_" + i + ".databasechangelog"));
            runs.add(timing("tenant_" + i));
        }
        assertTrue(overlap(runs), "tenant migrations never ran at the same time");
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

    private static boolean overlap(List<Timestamp[]> runs) {
        for (int i = 0; i < runs.size(); i++) {
            for (int j = i + 1; j < runs.size(); j++) {
                if (runs.get(i)[0].before(runs.get(j)[1]) && runs.get(j)[0].before(runs.get(i)[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AdminDataSourceProperties admin() {
        AdminDataSourceProperties admin = new AdminDataSourceProperties();
        admin.setJdbcUrl(postgres.getJdbcUrl());
        admin.setUserName(postgres.getUsername());
        admin.setPassword(postgres.getPassword());
        return admin;
    }

    private static TenantDatabaseProperties tenant(String schema) {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(postgres.getJdbcUrl());
        tenant.setSchema(schema);
        return tenant;
    }

    private static int count(String table) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static Timestamp[] timing(String schema) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT started, finished FROM " + schema + ".migration_timing")) {
            resultSet.next();
            return new Timestamp[] {resultSet.getTimestamp("started"), resultSet.getTimestamp("finished")};
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
