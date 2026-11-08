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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TenantMigrationRunner} (Phases 2 and 3).
 */
class TenantMigrationRunnerTest {

    private static final String DB_A = "jdbc:postgresql://db-a/orders_db";
    private static final String DB_B = "jdbc:postgresql://db-b/billing_db";
    private static final String TENANT_CHANGELOG = "db/changelog/db.changelog-tenant.xml";
    private static final int ROLLBACK_COUNT = 2;
    private static final int WORK_ARGUMENT = 4;

    private SqlDaoLiquibaseProperties properties;
    private MigrationDataSourceResolver resolver;
    private MigrationSession session;
    private SchemaMigrationLock schemaLock;
    private SchemaProvisioningService provisioningService;
    private LiquibaseMigrationService liquibaseService;
    private InfrastructureMigrationRunner infrastructureRunner;
    private MigrationConfigurationValidator validator;
    private Map<String, DataSource> targetDataSources;
    private DataSource migrationDataSource;
    private Connection connection;
    private TenantMigrationRunner runner;
    private final List<String> migrated = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new SqlDaoLiquibaseProperties();
        properties.setChangeLog(TENANT_CHANGELOG);
        resolver = mock(MigrationDataSourceResolver.class);
        session = mock(MigrationSession.class);
        when(resolver.openSession()).thenReturn(session);
        schemaLock = mock(SchemaMigrationLock.class);
        provisioningService = mock(SchemaProvisioningService.class);
        liquibaseService = mock(LiquibaseMigrationService.class);
        infrastructureRunner = mock(InfrastructureMigrationRunner.class);
        validator = mock(MigrationConfigurationValidator.class);
        targetDataSources = new HashMap<>();
        migrationDataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(session.resolve(anyString(), any(), any())).thenReturn(migrationDataSource);
        runner = new TenantMigrationRunner(properties, resolver, schemaLock, provisioningService, liquibaseService,
                infrastructureRunner, validator, targetDataSources);
        doAnswer(invocation -> {
            assertTrue(((ReentrantLock) runner.tenantLock(invocation.getArgument(1))).isHeldByCurrentThread());
            ((SchemaMigrationLock.LockedWork) invocation.getArgument(WORK_ARGUMENT)).execute(connection);
            return null;
        }).when(schemaLock).withLockedConnection(any(), anyString(), any(), anyInt(), any());
        doAnswer(invocation -> migrated.add(invocation.getArgument(1)))
                .when(liquibaseService).runMigrations(any(), anyString(), any(), anyString());
    }

    @Test
    void migratesEveryTenantInOrderOnItsResolvedDataSource() {
        DataSource pool = mock(DataSource.class);
        targetDataSources.put("b_tenant", pool);
        Set<String> failed = runner.migrateAllTenants(Map.of("b_tenant", tenant(DB_A, "schema_b"),
                "a_tenant", tenant(DB_A, "schema_a")), Set.of(), session);
        assertTrue(failed.isEmpty());
        assertEquals(List.of("a_tenant", "b_tenant"), migrated);
        verify(session).resolve(eq("b_tenant"), any(), eq(pool));
        verify(schemaLock).withLockedConnection(eq(migrationDataSource), eq("a_tenant"), eq("schema_a"),
                eq(SqlDaoLiquibaseProperties.DEFAULT_LOCK_TIMEOUT_SECONDS), any());
        verify(liquibaseService).runMigrations(connection, "a_tenant", "schema_a", TENANT_CHANGELOG);
        verify(provisioningService, never()).ensureSchema(any(), anyString(), anyString());
    }

    @Test
    void provisionsSchemaBeforeMigratingWhenEnabled() {
        properties.setProvisionSchemas(true);
        runner.migrateAllTenants(Map.of("t1", tenant(DB_A, "tenant1"), "t2", tenant(DB_A, null)), Set.of(), session);
        verify(provisioningService).ensureSchema(connection, "t1", "tenant1");
        verify(provisioningService, never()).ensureSchema(any(), eq("t2"), any());
    }

    @Test
    void skipsTenantsOfFailedDatabases() {
        Set<String> failed = runner.migrateAllTenants(Map.of("t1", tenant(DB_A, "tenant1"),
                "t2", tenant(DB_B, "tenant2")), Set.of(JdbcUrlNormalizer.normalize(DB_A)), session);
        assertEquals(Set.of("t1"), failed);
        assertEquals(List.of("t2"), migrated);
    }

    @Test
    void migratesSharedSchemaOnceButNeverDeduplicatesTenantsWithoutSchema() {
        runner.migrateAllTenants(Map.of("t1", tenant(DB_A, "shared_schema"), "t2", tenant(DB_A, "shared_schema"),
                "t3", tenant(DB_A, null), "t4", tenant(DB_A, null)), Set.of(), session);
        assertEquals(List.of("t1", "t3", "t4"), migrated);
    }

    @Test
    void failOnErrorStopsAtFirstFailure() {
        doThrow(new MigrationException("x", "t1", MigrationErrorCode.MIGRATION_FAILED))
                .when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        assertThrows(MigrationException.class, () -> runner.migrateAllTenants(
                Map.of("t1", tenant(DB_A, "tenant1"), "t2", tenant(DB_A, "tenant2")), Set.of(), session));
        assertFalse(migrated.contains("t2"));
    }

    @Test
    void withoutFailOnErrorRemainingTenantsAreMigrated() {
        properties.setFailOnError(false);
        doThrow(new MigrationException("x", "t1", MigrationErrorCode.MIGRATION_FAILED))
                .when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        Set<String> failed = runner.migrateAllTenants(
                Map.of("t1", tenant(DB_A, "tenant1"), "t2", tenant(DB_A, "tenant2")), Set.of(), session);
        assertEquals(Set.of("t1"), failed);
        assertEquals(List.of("t2"), migrated);
    }

    @Test
    void runtimeMigrationRunsPhaseOneFirstAndClosesSession() {
        properties.setPublicChangeLog("db/changelog/db.changelog-public.xml");
        DatabaseTarget target = new DatabaseTarget("db-a:5432/orders_db", DB_A, List.of("t1"), Set.of(), "ordersdb");
        TenantDatabaseProperties tenant = tenant(DB_A, "tenant1");
        DataSource newPool = mock(DataSource.class);
        when(resolver.databaseOf("t1", tenant)).thenReturn(target);
        when(infrastructureRunner.migrateDatabase(target, session)).thenReturn(true);
        assertTrue(runner.migrateTenant("t1", tenant, newPool));
        verify(session).resolve("t1", tenant, newPool);
        assertEquals(List.of("t1"), migrated);
        verify(session).close();
    }

    @Test
    void runtimeMigrationReportsFailuresThroughReturnValue() {
        properties.setPublicChangeLog("db/changelog/db.changelog-public.xml");
        TenantDatabaseProperties tenant = tenant(DB_A, "tenant1");
        when(infrastructureRunner.migrateDatabase(any(), eq(session))).thenReturn(false);
        assertFalse(runner.migrateTenant("t1", tenant, mock(DataSource.class)));
        assertTrue(migrated.isEmpty());

        properties.setPublicChangeLog(null);
        doThrow(new MigrationException("x", "t1", MigrationErrorCode.MIGRATION_FAILED))
                .when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        assertFalse(runner.migrateTenant("t1", tenant, mock(DataSource.class)));
        assertFalse(((ReentrantLock) runner.tenantLock("t1")).isLocked());
    }

    @Test
    void rollbackRunsUnderTheSchemaLock() {
        TenantDatabaseProperties tenant = tenant(DB_A, "tenant1");
        runner.rollbackTenant("t1", tenant, null, ROLLBACK_COUNT);
        verify(liquibaseService).rollbackMigrations(connection, "t1", "tenant1", ROLLBACK_COUNT);
        verify(session).close();
    }

    @Test
    void tenantLockIsStablePerTenant() {
        assertSame(runner.tenantLock("t1"), runner.tenantLock("t1"));
        assertFalse(runner.tenantLock("t1") == runner.tenantLock("t2"));
    }

    @Test
    void delegatesTenantValidation() {
        TenantDatabaseProperties tenant = tenant(DB_A, "tenant1");
        when(validator.validateTenant("t1", tenant)).thenReturn(List.of("problem"));
        assertEquals(List.of("problem"), runner.validateTenant("t1", tenant));
        verify(schemaLock, never()).withLockedConnection(any(), isNull(), any(), anyInt(), any());
    }

    private static TenantDatabaseProperties tenant(String jdbcUrl, String schema) {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(jdbcUrl);
        tenant.setSchema(schema);
        return tenant;
    }
}
