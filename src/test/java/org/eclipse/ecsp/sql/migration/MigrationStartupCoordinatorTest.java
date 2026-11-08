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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MigrationStartupCoordinator}.
 */
class MigrationStartupCoordinatorTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    private SqlDaoLiquibaseProperties properties;
    private MigrationConfigurationValidator validator;
    private MigrationDataSourceResolver resolver;
    private MigrationSession session;
    private InfrastructureMigrationRunner infrastructureRunner;
    private TenantMigrationRunner tenantRunner;
    private ApplicationEventPublisher publisher;
    private Map<String, TenantDatabaseProperties> tenantConfigMap;
    private MigrationStartupCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties = new SqlDaoLiquibaseProperties();
        properties.setChangeLog("db/changelog/db.changelog-tenant.xml");
        validator = mock(MigrationConfigurationValidator.class);
        resolver = mock(MigrationDataSourceResolver.class);
        session = mock(MigrationSession.class);
        when(resolver.openSession()).thenReturn(session);
        infrastructureRunner = mock(InfrastructureMigrationRunner.class);
        tenantRunner = mock(TenantMigrationRunner.class);
        when(infrastructureRunner.runInfrastructureMigrations(anyMap(), any())).thenReturn(Set.of());
        when(tenantRunner.migrateAllTenants(anyMap(), anySet(), any())).thenReturn(Set.of());
        publisher = mock(ApplicationEventPublisher.class);
        tenantConfigMap = new HashMap<>();
        tenantConfigMap.put("default", new TenantDatabaseProperties());
        tenantConfigMap.put("tenant1", new TenantDatabaseProperties());
        coordinator = coordinator(true);
    }

    @AfterEach
    void tearDown() {
        coordinator.destroy();
    }

    @Test
    void blockingModeValidatesAndRunsPhasesInOrder() {
        coordinator.afterPropertiesSet();
        verify(validator).validate(tenantConfigMap);
        verify(infrastructureRunner).runInfrastructureMigrations(tenantConfigMap, session);
        verify(tenantRunner).migrateAllTenants(tenantConfigMap, Set.of(), session);
        verify(session).close();
    }

    @Test
    void phaseOneFailuresAreSkippedInPhaseThree() {
        when(infrastructureRunner.runInfrastructureMigrations(anyMap(), any())).thenReturn(Set.of("db"));
        coordinator.afterPropertiesSet();
        verify(tenantRunner).migrateAllTenants(tenantConfigMap, Set.of("db"), session);
    }

    @Test
    void configurationErrorsFailStartupBeforeAnyMigration() {
        doThrow(new MigrationException("bad", null, MigrationErrorCode.INVALID_CONFIGURATION))
                .when(validator).validate(anyMap());
        assertThrows(MigrationException.class, () -> coordinator.afterPropertiesSet());
        verify(resolver, never()).openSession();
    }

    @Test
    void blockingModeFailurePropagates() {
        when(tenantRunner.migrateAllTenants(anyMap(), anySet(), any()))
                .thenThrow(new MigrationException("x", "tenant1", MigrationErrorCode.MIGRATION_FAILED));
        assertThrows(MigrationException.class, () -> coordinator.afterPropertiesSet());
        verify(session).close();
    }

    @Test
    void runOnStartupFalseOnlyValidates() {
        properties.setRunOnStartup(false);
        coordinator.afterPropertiesSet();
        verify(validator).validate(anyMap());
        verify(resolver, never()).openSession();
    }

    @Test
    void singleTenantModeMigratesOnlyTheDefaultTenant() {
        coordinator = coordinator(false);
        coordinator.afterPropertiesSet();
        verify(tenantRunner).migrateAllTenants(Map.of("default", tenantConfigMap.get("default")), Set.of(), session);
    }

    @Test
    void afterReadyModeRunsOnceWhenTrafficIsAccepted() {
        properties.setStartupMode(StartupMode.AFTER_READY);
        coordinator.afterPropertiesSet();
        verify(validator).validate(anyMap());
        verify(resolver, never()).openSession();

        coordinator.onAcceptingTraffic(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        coordinator.onAcceptingTraffic(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        coordinator.onAcceptingTraffic(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> verify(session).close());
        verify(tenantRunner, times(1)).migrateAllTenants(anyMap(), anySet(), any());
    }

    @Test
    void afterReadyFailureRefusesTrafficWhenFailOnError() {
        properties.setStartupMode(StartupMode.AFTER_READY);
        when(tenantRunner.migrateAllTenants(anyMap(), anySet(), any()))
                .thenThrow(new MigrationException("x", "tenant1", MigrationErrorCode.MIGRATION_FAILED));
        coordinator.runAfterReady();
        ArgumentCaptor<AvailabilityChangeEvent<?>> event = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, event.getValue().getState());
    }

    @Test
    void afterReadyFailureKeepsServingWithoutFailOnError() {
        properties.setStartupMode(StartupMode.AFTER_READY);
        properties.setFailOnError(false);
        when(tenantRunner.migrateAllTenants(anyMap(), anySet(), any())).thenThrow(new IllegalStateException("x"));
        coordinator.runAfterReady();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    private MigrationStartupCoordinator coordinator(boolean multitenancyEnabled) {
        return new MigrationStartupCoordinator(properties, validator, resolver, infrastructureRunner, tenantRunner,
                publisher, tenantConfigMap, multitenancyEnabled);
    }
}
