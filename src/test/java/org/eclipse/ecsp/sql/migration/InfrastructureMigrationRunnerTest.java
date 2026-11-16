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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InfrastructureMigrationRunner} (Phase 1).
 */
class InfrastructureMigrationRunnerTest {

    private static final String PUBLIC_CHANGELOG = "db/changelog/db.changelog-public.xml";
    private static final int WORK_ARGUMENT = 4;
    private static final int DATABASE_COUNT = 2;
    private static final int ATTEMPTS_AFTER_ONE_RETRY = 2;

    private SqlDaoLiquibaseProperties properties;
    private MigrationDataSourceResolver resolver;
    private MigrationSession session;
    private SchemaMigrationLock schemaLock;
    private LiquibaseMigrationService liquibaseService;
    private Connection connection;
    private InfrastructureMigrationRunner runner;
    private DatabaseTarget orders;
    private DatabaseTarget billing;

    @BeforeEach
    void setUp() {
        properties = new SqlDaoLiquibaseProperties();
        properties.setPublicChangeLog(PUBLIC_CHANGELOG);
        properties.getRetry().setDelayMs(0);
        resolver = mock(MigrationDataSourceResolver.class);
        session = mock(MigrationSession.class);
        schemaLock = mock(SchemaMigrationLock.class);
        liquibaseService = mock(LiquibaseMigrationService.class);
        connection = mock(Connection.class);
        runner = new InfrastructureMigrationRunner(properties, resolver, schemaLock, liquibaseService,
                new MigrationRetryExecutor(properties));
        orders = new DatabaseTarget("db-a:5432/orders_db", "jdbc:postgresql://db-a/orders_db",
                List.of("eu_orders", "us_orders"), "ordersdb");
        billing = new DatabaseTarget("db-b:5432/billing_db", "jdbc:postgresql://db-b/billing_db",
                List.of("eu_billing", "us_billing"), "billingdb");
        when(resolver.distinctDatabases(anyMap())).thenReturn(List.of(orders, billing));
        when(session.resolveForDatabase(any())).thenReturn(mock(DataSource.class));
        doAnswer(invocation -> {
            ((SchemaMigrationLock.LockedWork) invocation.getArgument(WORK_ARGUMENT)).execute(connection);
            return null;
        }).when(schemaLock).withLockedConnection(any(), isNull(), eq("public"), anyInt(), any());
    }

    @Test
    void runsPublicChangelogOncePerDistinctDatabase() {
        Set<String> failed = runner.runInfrastructureMigrations(Map.of(), session);
        assertTrue(failed.isEmpty());
        verify(session).resolveForDatabase(orders);
        verify(session).resolveForDatabase(billing);
        verify(liquibaseService, times(DATABASE_COUNT)).runPublicMigrations(connection, "public", PUBLIC_CHANGELOG);
    }

    @Test
    void skipsPhaseOneWithoutPublicChangelog() {
        properties.setPublicChangeLog(null);
        assertTrue(runner.runInfrastructureMigrations(Map.of("t1", new TenantDatabaseProperties()), session)
                .isEmpty());
        verify(resolver, never()).distinctDatabases(anyMap());
    }

    @Test
    void reportsFailedDatabaseWithoutFailOnError() {
        properties.setFailOnError(false);
        when(session.resolveForDatabase(billing))
                .thenThrow(new MigrationException("x", null, MigrationErrorCode.MIGRATION_FAILED));
        assertEquals(Set.of(billing.key()), runner.runInfrastructureMigrations(Map.of(), session));
    }

    @Test
    void failOnErrorPropagatesPhaseOneFailure() {
        when(session.resolveForDatabase(orders))
                .thenThrow(new MigrationException("x", null, MigrationErrorCode.INVALID_CONFIGURATION));
        assertThrows(MigrationException.class, () -> runner.runInfrastructureMigrations(Map.of(), session));
        verify(session, never()).resolveForDatabase(billing);
    }

    @Test
    void retriesFailureOfOneDatabase() {
        when(session.resolveForDatabase(orders)).thenThrow(failure()).thenReturn(mock(DataSource.class));
        assertTrue(runner.runInfrastructureMigrations(Map.of(), session).isEmpty());
        verify(session, times(ATTEMPTS_AFTER_ONE_RETRY)).resolveForDatabase(orders);
        verify(liquibaseService, times(DATABASE_COUNT)).runPublicMigrations(connection, "public", PUBLIC_CHANGELOG);
    }

    @Test
    void reportsDatabaseWhoseFailureOutlastsTheRetries() {
        properties.setFailOnError(false);
        when(session.resolveForDatabase(billing)).thenThrow(failure());
        assertEquals(Set.of(billing.key()), runner.runInfrastructureMigrations(Map.of(), session));
        verify(session, times(MigrationRetryProperties.DEFAULT_MAX_ATTEMPTS)).resolveForDatabase(billing);
    }

    private static MigrationException failure() {
        return new MigrationException("connection lost", null, MigrationErrorCode.MIGRATION_FAILED,
                new SQLException("connection failure", "08006"));
    }
}
