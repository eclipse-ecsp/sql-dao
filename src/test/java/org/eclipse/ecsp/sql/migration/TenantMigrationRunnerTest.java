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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.mockito.Mockito.times;
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
    private static final int PARALLEL_TENANTS = 3;
    private static final int TWO_WORKERS = 2;
    private static final int MANY_TENANTS = 6;
    private static final int AWAIT_SECONDS = 10;
    private static final long SLOW_MIGRATION_MILLIS = 100L;

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
    private final List<String> migrated = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        properties = new SqlDaoLiquibaseProperties();
        properties.setChangeLog(TENANT_CHANGELOG);
        properties.getRetry().setDelayMs(0);
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
                infrastructureRunner, validator, new MigrationRetryExecutor(properties), targetDataSources);
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
    }

    @Test
    void provisionsSchemaBeforeMigratingWhenSchemaConfigured() {
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
    void parallelismRunsTenantsAtTheSameTime() {
        properties.setParallelism(PARALLEL_TENANTS);
        CyclicBarrier allStarted = new CyclicBarrier(PARALLEL_TENANTS);
        doAnswer(invocation -> {
            allStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            return migrated.add(invocation.getArgument(1));
        }).when(liquibaseService).runMigrations(any(), anyString(), any(), anyString());
        Set<String> failed = runner.migrateAllTenants(tenants(PARALLEL_TENANTS), Set.of(), session);
        assertTrue(failed.isEmpty());
        assertEquals(PARALLEL_TENANTS, migrated.size());
    }

    @Test
    void parallelismIsBoundedByTheConfiguredWorkers() {
        properties.setParallelism(TWO_WORKERS);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        doAnswer(invocation -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(SLOW_MIGRATION_MILLIS);
            running.decrementAndGet();
            return migrated.add(invocation.getArgument(1));
        }).when(liquibaseService).runMigrations(any(), anyString(), any(), anyString());
        runner.migrateAllTenants(tenants(MANY_TENANTS), Set.of(), session);
        assertEquals(MANY_TENANTS, migrated.size());
        assertTrue(peak.get() <= TWO_WORKERS, "peak concurrency was " + peak.get());
    }

    @Test
    void parallelRunStillSkipsFailedDatabasesAndDeduplicatesSharedSchemas() {
        properties.setParallelism(PARALLEL_TENANTS);
        Map<String, TenantDatabaseProperties> tenants = new HashMap<>();
        tenants.put("t1", tenant(DB_A, "shared_schema"));
        tenants.put("t2", tenant(DB_A, "shared_schema"));
        tenants.put("t3", tenant(DB_A, "tenant3"));
        tenants.put("t4", tenant(DB_B, "tenant4"));
        Set<String> failed = runner.migrateAllTenants(tenants, Set.of(JdbcUrlNormalizer.normalize(DB_B)), session);
        assertEquals(Set.of("t4"), failed);
        assertEquals(List.of("t1", "t3"), migrated.stream().sorted().toList());
    }

    @Test
    void parallelRunWithoutFailOnErrorMigratesTheRestAndReportsFailures() {
        properties.setParallelism(PARALLEL_TENANTS);
        properties.setFailOnError(false);
        doThrow(new MigrationException("x", "t2", MigrationErrorCode.MIGRATION_FAILED))
                .when(liquibaseService).runMigrations(any(), eq("t2"), any(), anyString());
        Set<String> failed = runner.migrateAllTenants(tenants(MANY_TENANTS), Set.of(), session);
        assertEquals(Set.of("t2"), failed);
        assertEquals(MANY_TENANTS - 1, migrated.size());
        assertFalse(migrated.contains("t2"));
    }

    @Test
    void parallelFailOnErrorStopsNewTenantsButLetsRunningOnesFinish() {
        properties.setParallelism(TWO_WORKERS);
        CountDownLatch t2Started = new CountDownLatch(1);
        CountDownLatch t1Failed = new CountDownLatch(1);
        doAnswer(invocation -> {
            t2Started.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            t1Failed.countDown();
            throw new MigrationException("x", "t1", MigrationErrorCode.MIGRATION_FAILED);
        }).when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        doAnswer(invocation -> {
            t2Started.countDown();
            t1Failed.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            // t1's worker must have flagged the abort before this worker looks for its next tenant
            Thread.sleep(SLOW_MIGRATION_MILLIS);
            return migrated.add(invocation.getArgument(1));
        }).when(liquibaseService).runMigrations(any(), eq("t2"), any(), anyString());
        MigrationException exception = assertThrows(MigrationException.class,
                () -> runner.migrateAllTenants(tenants(MANY_TENANTS), Set.of(), session));
        assertEquals("t1", exception.getTenantId());
        assertEquals(List.of("t2"), migrated);
        assertFalse(((ReentrantLock) runner.tenantLock("t1")).isLocked());
    }

    @Test
    void parallelFailOnErrorReportsConcurrentFailuresAsSuppressed() {
        properties.setParallelism(TWO_WORKERS);
        CyclicBarrier bothRunning = new CyclicBarrier(TWO_WORKERS);
        doAnswer(invocation -> {
            bothRunning.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            throw new MigrationException("x", invocation.getArgument(1), MigrationErrorCode.MIGRATION_FAILED);
        }).when(liquibaseService).runMigrations(any(), anyString(), any(), anyString());
        MigrationException exception = assertThrows(MigrationException.class,
                () -> runner.migrateAllTenants(tenants(TWO_WORKERS), Set.of(), session));
        assertEquals(1, exception.getSuppressed().length);
        assertTrue(migrated.isEmpty());
    }

    @Test
    void retriesFailureWithFreshConnectionAndLock() {
        doThrow(failure("t1")).doAnswer(invocation -> migrated.add(invocation.getArgument(1)))
                .when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        assertTrue(runner.migrateAllTenants(Map.of("t1", tenant(DB_A, "tenant1")), Set.of(), session).isEmpty());
        assertEquals(List.of("t1"), migrated);
        verify(session, times(TWO_WORKERS)).resolve(eq("t1"), any(), any());
        verify(schemaLock, times(TWO_WORKERS)).withLockedConnection(any(), eq("t1"), eq("tenant1"), anyInt(), any());
        verify(provisioningService, times(TWO_WORKERS)).ensureSchema(connection, "t1", "tenant1");
    }

    @Test
    void retriesWhenTheAdminPoolCannotBeCreatedYet() {
        doThrow(failure("t1")).doReturn(migrationDataSource)
                .when(session).resolve(eq("t1"), any(), any());
        assertTrue(runner.migrateAllTenants(Map.of("t1", tenant(DB_A, "tenant1")), Set.of(), session).isEmpty());
        assertEquals(List.of("t1"), migrated);
    }

    @Test
    void givesUpWhenFailureOutlastsTheAttempts() {
        doThrow(failure("t1")).when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        assertThrows(MigrationException.class,
                () -> runner.migrateAllTenants(Map.of("t1", tenant(DB_A, "tenant1")), Set.of(), session));
        verify(liquibaseService, times(MigrationRetryProperties.DEFAULT_MAX_ATTEMPTS))
                .runMigrations(any(), eq("t1"), any(), anyString());
    }

    @Test
    void retriesFailuresThatCannotSucceedToo() {
        doThrow(new MigrationException("x", "t1", MigrationErrorCode.MIGRATION_FAILED,
                new SQLException("syntax error", "42601")))
                .when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        assertThrows(MigrationException.class,
                () -> runner.migrateAllTenants(Map.of("t1", tenant(DB_A, "tenant1")), Set.of(), session));
        verify(liquibaseService, times(MigrationRetryProperties.DEFAULT_MAX_ATTEMPTS))
                .runMigrations(any(), eq("t1"), any(), anyString());
    }

    @Test
    void retryReleasesTheTenantLockAndKeepsOtherTenantsMigrating() {
        properties.setFailOnError(false);
        doThrow(failure("t1")).when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        Set<String> failed = runner.migrateAllTenants(
                Map.of("t1", tenant(DB_A, "tenant1"), "t2", tenant(DB_A, "tenant2")), Set.of(), session);
        assertEquals(Set.of("t1"), failed);
        assertEquals(List.of("t2"), migrated);
        assertFalse(((ReentrantLock) runner.tenantLock("t1")).isLocked());
    }

    @Test
    void runtimeMigrationRetriesToo() {
        doThrow(failure("t1")).doAnswer(invocation -> migrated.add(invocation.getArgument(1)))
                .when(liquibaseService).runMigrations(any(), eq("t1"), any(), anyString());
        assertTrue(runner.migrateTenant("t1", tenant(DB_A, "tenant1"), mock(DataSource.class)));
        assertEquals(List.of("t1"), migrated);
    }

    @Test
    void rollbackIsNeverRetried() {
        doThrow(failure("t1")).when(liquibaseService).rollbackMigrations(any(), eq("t1"), any(), anyInt());
        assertThrows(MigrationException.class,
                () -> runner.rollbackTenant("t1", tenant(DB_A, "tenant1"), null, ROLLBACK_COUNT));
        verify(liquibaseService).rollbackMigrations(any(), eq("t1"), any(), anyInt());
    }

    @Test
    void runtimeMigrationRunsPhaseOneFirstAndClosesSession() {
        properties.setPublicChangeLog("db/changelog/db.changelog-public.xml");
        DatabaseTarget target = new DatabaseTarget("db-a:5432/orders_db", DB_A, List.of("t1"), "ordersdb");
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

    private static MigrationException failure(String tenantId) {
        return new MigrationException("connection lost", tenantId, MigrationErrorCode.MIGRATION_FAILED,
                new SQLException("connection failure", "08006"));
    }

    private static Map<String, TenantDatabaseProperties> tenants(int count) {
        Map<String, TenantDatabaseProperties> tenants = new TreeMap<>();
        for (int i = 1; i <= count; i++) {
            tenants.put("t" + i, tenant(DB_A, "tenant" + i));
        }
        return tenants;
    }

    private static TenantDatabaseProperties tenant(String jdbcUrl, String schema) {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(jdbcUrl);
        tenant.setSchema(schema);
        return tenant;
    }
}
