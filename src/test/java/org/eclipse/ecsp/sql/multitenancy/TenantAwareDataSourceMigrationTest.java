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

package org.eclipse.ecsp.sql.multitenancy;

import org.eclipse.ecsp.sql.authentication.CredentialsProvider;
import org.eclipse.ecsp.sql.migration.TenantMigrationRunner;
import org.eclipse.ecsp.sql.postgress.config.PostgresDbConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runtime onboarding with Liquibase enabled: migrate before the route swap.
 */
class TenantAwareDataSourceMigrationTest {

    private static final String TENANT = "tenant1";

    private TenantAwareDataSource tenantAwareDataSource;
    private PostgresDbConfig postgresDbConfig;
    private TenantMigrationRunner migrationRunner;
    private Map<String, DataSource> targetDataSources;
    private Map<String, TenantDatabaseProperties> tenantConfigMap;
    private DataSource previousPool;
    private DataSource newPool;
    private CredentialsProvider previousProvider;
    private TenantDatabaseProperties previousProperties;
    private TenantDatabaseProperties newProperties;
    private CountingLock tenantLock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        postgresDbConfig = mock(PostgresDbConfig.class);
        migrationRunner = mock(TenantMigrationRunner.class);
        tenantLock = new CountingLock();
        when(migrationRunner.tenantLock(TENANT)).thenReturn(tenantLock);
        when(migrationRunner.validateTenant(anyString(), any())).thenReturn(List.of());
        when(migrationRunner.isFailOnError()).thenReturn(true);
        ObjectProvider<TenantMigrationRunner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(migrationRunner);

        previousPool = mock(DataSource.class);
        newPool = pool();
        previousProvider = mock(CredentialsProvider.class);
        previousProperties = new TenantDatabaseProperties();
        newProperties = new TenantDatabaseProperties();
        newProperties.setSchema("tenant1_schema");
        when(postgresDbConfig.findCredentialsProvider(TENANT)).thenReturn(Optional.of(previousProvider));
        when(postgresDbConfig.initDataSource(eq(TENANT), any())).thenReturn(newPool);

        targetDataSources = new HashMap<>(Map.of(TENANT, previousPool));
        tenantConfigMap = new HashMap<>(Map.of(TENANT, previousProperties));
        tenantAwareDataSource = new TenantAwareDataSource();
        ReflectionTestUtils.setField(tenantAwareDataSource, "isMultitenancyEnabled", true);
        ReflectionTestUtils.setField(tenantAwareDataSource, "targetDataSources", targetDataSources);
        ReflectionTestUtils.setField(tenantAwareDataSource, "postgresDbConfig", postgresDbConfig);
        ReflectionTestUtils.setField(tenantAwareDataSource, "tenantConfigMap", tenantConfigMap);
        ReflectionTestUtils.setField(tenantAwareDataSource, "migrationRunnerProvider", provider);
        tenantAwareDataSource.dataSource();
    }

    @Test
    void migratesBeforeTheRouteIsSwapped() {
        when(migrationRunner.migrateTenant(TENANT, newProperties, newPool)).thenAnswer(invocation -> {
            assertSame(previousPool, targetDataSources.get(TENANT));
            assertTrue(tenantLock.isHeldByCurrentThread());
            return true;
        });
        assertTrue(tenantAwareDataSource.addOrUpdateTenantDataSource(TENANT, newProperties));
        assertSame(newPool, targetDataSources.get(TENANT));
        assertSame(newProperties, tenantConfigMap.get(TENANT));
        verify(postgresDbConfig).cleanupDataSource(previousPool);
        verify(postgresDbConfig, never()).restoreCredentialsProvider(anyString(), any());
        assertEquals(1, tenantLock.unlocks);
    }

    @Test
    void failedMigrationKeepsPreviousRouteAndRestoresCredentials() {
        when(migrationRunner.migrateTenant(TENANT, newProperties, newPool)).thenReturn(false);
        assertFalse(tenantAwareDataSource.addOrUpdateTenantDataSource(TENANT, newProperties));
        assertSame(previousPool, targetDataSources.get(TENANT));
        assertSame(previousProperties, tenantConfigMap.get(TENANT));
        verify(postgresDbConfig).cleanupDataSource(newPool);
        verify(postgresDbConfig, never()).cleanupDataSource(previousPool);
        verify(postgresDbConfig).restoreCredentialsProvider(TENANT, previousProvider);
    }

    @Test
    void withoutFailOnErrorTheTenantIsRegisteredAnyway() {
        when(migrationRunner.isFailOnError()).thenReturn(false);
        when(migrationRunner.migrateTenant(TENANT, newProperties, newPool)).thenReturn(false);
        assertTrue(tenantAwareDataSource.addOrUpdateTenantDataSource(TENANT, newProperties));
        assertSame(newPool, targetDataSources.get(TENANT));
    }

    @Test
    void configurationErrorsAreRejectedBeforeAnyPoolIsCreated() throws Exception {
        when(migrationRunner.validateTenant(TENANT, newProperties)).thenReturn(List.of("bad schema"));
        when(migrationRunner.isFailOnError()).thenReturn(false);
        assertFalse(tenantAwareDataSource.addOrUpdateTenantDataSource(TENANT, newProperties));
        verify(postgresDbConfig, never()).initDataSource(anyString(), any());
    }

    @Test
    void newTenantFailureRemovesItsCredentialsProvider() throws Exception {
        when(postgresDbConfig.findCredentialsProvider("tenant2")).thenReturn(Optional.empty());
        when(migrationRunner.tenantLock("tenant2")).thenReturn(new ReentrantLock());
        when(migrationRunner.migrateTenant(eq("tenant2"), any(), any())).thenReturn(false);
        when(postgresDbConfig.initDataSource(eq("tenant2"), any())).thenReturn(newPool);
        assertFalse(tenantAwareDataSource.addOrUpdateTenantDataSource("tenant2", newProperties));
        assertFalse(targetDataSources.containsKey("tenant2"));
        verify(postgresDbConfig).restoreCredentialsProvider("tenant2", null);
    }

    @Test
    void removalTakesTheTenantLock() {
        assertTrue(tenantAwareDataSource.removeTenantDataSource(TENANT));
        assertEquals(1, tenantLock.locks);
        assertEquals(1, tenantLock.unlocks);
        assertFalse(targetDataSources.containsKey(TENANT));
    }

    @Test
    void withoutLiquibaseTheRouteIsSwappedImmediately() {
        ReflectionTestUtils.setField(tenantAwareDataSource, "migrationRunnerProvider", null);
        assertTrue(tenantAwareDataSource.addOrUpdateTenantDataSource(TENANT, newProperties));
        assertNotSame(previousPool, targetDataSources.get(TENANT));
        verify(migrationRunner, never()).migrateTenant(anyString(), any(), any());
    }

    private static DataSource pool() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        return dataSource;
    }

    /**
     * Lock that counts lock and unlock calls.
     */
    private static final class CountingLock extends ReentrantLock {

        private static final long serialVersionUID = 1L;
        private int locks;
        private int unlocks;

        @Override
        public void lock() {
            locks++;
            super.lock();
        }

        @Override
        public void unlock() {
            unlocks++;
            super.unlock();
        }
    }
}
