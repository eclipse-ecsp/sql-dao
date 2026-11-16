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

import io.prometheus.client.CollectorRegistry;
import org.eclipse.ecsp.sql.multitenancy.TenantContext;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Starts the PostgreSQL that the Spring-context migration tests use and gives them JDBC helpers. The database URL is
 * published as {@code DB_URL_LIQUIBASE}, which {@code application-dao-liquibase-test.properties} refers to. The Spring
 * context is created after this class's {@code @BeforeAll} has run.
 */
abstract class LiquibaseSpringTestSupport {

    private static final String DB_URL_PROPERTY = "DB_URL_LIQUIBASE";
    private static final String MULTITENANCY_PROPERTY = "multitenancy.enabled";
    private static final String CREDENTIALS_PROVIDER =
            "org.eclipse.ecsp.sql.authentication.PostgresMultiTenantTestCredentialsProvider";
    private static final int MAX_POOL_SIZE = 5;
    // Spring closes a test context only after the class's @AfterAll, so its pools are closed against a stopped
    // database. A short connection timeout keeps that from taking a minute per pool.
    private static final int CONNECTION_TIMEOUT_MS = 1000;

    static PostgreSQLContainer<?> postgres;
    private static String previousMultitenancy;

    @BeforeAll
    @SuppressWarnings("resource")
    static void startDatabase() {
        CollectorRegistry.defaultRegistry.clear();
        postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("liquibase_db")
                .withUsername("root")
                .withPassword("root");
        postgres.start();
        previousMultitenancy = System.getProperty(MULTITENANCY_PROPERTY);
        System.setProperty(MULTITENANCY_PROPERTY, "true");
        System.setProperty(DB_URL_PROPERTY, postgres.getJdbcUrl());
    }

    @AfterAll
    static void stopDatabase() {
        TenantContext.clear();
        CollectorRegistry.defaultRegistry.clear();
        System.clearProperty(DB_URL_PROPERTY);
        if (previousMultitenancy == null) {
            System.clearProperty(MULTITENANCY_PROPERTY);
        } else {
            System.setProperty(MULTITENANCY_PROPERTY, previousMultitenancy);
        }
        postgres.stop();
    }

    static TenantDatabaseProperties tenantProperties(String jdbcUrl, String schema, String poolName) {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(jdbcUrl);
        tenant.setSchema(schema);
        tenant.setUserName(postgres.getUsername());
        tenant.setPassword(postgres.getPassword());
        tenant.setDriverClassName("org.postgresql.Driver");
        tenant.setPoolName(poolName);
        tenant.setMaxPoolSize(MAX_POOL_SIZE);
        tenant.setConnectionTimeoutMs(CONNECTION_TIMEOUT_MS);
        tenant.setCredentialProviderBeanName(CREDENTIALS_PROVIDER);
        return tenant;
    }

    static String scalar(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    static int count(String table) throws SQLException {
        return Integer.parseInt(scalar("SELECT count(*) FROM " + table));
    }

    static boolean schemaExists(String schema) throws SQLException {
        return count("pg_namespace WHERE nspname = '" + schema + "'") > 0;
    }

    static String changelogFingerprint(String schema) throws SQLException {
        return scalar("SELECT count(*) || '/' || max(orderexecuted) || '/' || max(dateexecuted)::text FROM "
                + schema + ".databasechangelog");
    }
}
