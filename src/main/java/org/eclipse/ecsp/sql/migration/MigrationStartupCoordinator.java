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

import org.eclipse.ecsp.sql.dao.constants.MultitenantConstants;
import org.eclipse.ecsp.sql.migration.MigrationDataSourceResolver.MigrationSession;
import org.eclipse.ecsp.sql.migration.SqlDaoLiquibaseProperties.StartupMode;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the startup migration phases.
 *
 * <p>In {@code blocking} mode the phases run in {@link #afterPropertiesSet()}. This bean is reported as a
 * database initializer by {@link MigrationDatabaseInitializerDetector}, so the {@code EntityManagerFactory}, JDBC
 * templates, and {@code @DependsOnDatabaseInitialization} beans are created only after migrations complete. In
 * {@code after-ready} mode the phases run on a single background thread once the application accepts traffic.
 * Configuration checks always run in {@link #afterPropertiesSet()} and always fail startup.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class MigrationStartupCoordinator implements InitializingBean, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationStartupCoordinator.class);
    private static final int SNAPSHOT_ATTEMPTS = 3;
    private static final String THREAD_NAME = "sqldao-liquibase-migration";

    private final SqlDaoLiquibaseProperties properties;
    private final MigrationConfigurationValidator validator;
    private final MigrationDataSourceResolver resolver;
    private final InfrastructureMigrationRunner infrastructureRunner;
    private final TenantMigrationRunner tenantRunner;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, TenantDatabaseProperties> tenantConfigMap;
    private final boolean multitenancyEnabled;
    private final AtomicBoolean afterReadyStarted = new AtomicBoolean();
    private volatile ExecutorService executor;

    /**
     * Creates the coordinator.
     *
     * @param properties the Liquibase migration properties
     * @param validator the configuration validator
     * @param resolver the migration datasource resolver
     * @param infrastructureRunner the Phase 1 runner
     * @param tenantRunner the Phase 2 and 3 runner
     * @param eventPublisher publisher used to refuse traffic after an {@code after-ready} failure
     * @param tenantConfigMap the configured tenants
     * @param multitenancyEnabled whether multitenancy is enabled
     */
    public MigrationStartupCoordinator(SqlDaoLiquibaseProperties properties, MigrationConfigurationValidator validator,
            MigrationDataSourceResolver resolver, InfrastructureMigrationRunner infrastructureRunner,
            TenantMigrationRunner tenantRunner, ApplicationEventPublisher eventPublisher,
            @Qualifier("tenantConfigMap") Map<String, TenantDatabaseProperties> tenantConfigMap,
            @Value("${" + MultitenantConstants.MULTITENANCY_ENABLED + ":false}") boolean multitenancyEnabled) {
        this.properties = properties;
        this.validator = validator;
        this.resolver = resolver;
        this.infrastructureRunner = infrastructureRunner;
        this.tenantRunner = tenantRunner;
        this.eventPublisher = eventPublisher;
        this.tenantConfigMap = tenantConfigMap;
        this.multitenancyEnabled = multitenancyEnabled;
    }

    /**
     * Validates the configuration and, in {@code blocking} mode, runs the startup phases.
     *
     * @throws MigrationException on invalid configuration, or on a migration failure with {@code fail-on-error=true}
     */
    @Override
    public void afterPropertiesSet() {
        Map<String, TenantDatabaseProperties> tenants = snapshot();
        this.validator.validate(tenants);
        if (!this.properties.isRunOnStartup()) {
            LOGGER.info("sqldao.liquibase.run-on-startup=false; no startup migrations");
            return;
        }
        if (this.properties.getStartupMode() == StartupMode.BLOCKING) {
            runPhases(tenants);
        } else {
            LOGGER.info("Startup migrations deferred until the application accepts traffic");
        }
    }

    /**
     * Starts the {@code after-ready} migrations when the application first accepts traffic.
     *
     * @param event the readiness change
     */
    @EventListener
    public void onAcceptingTraffic(AvailabilityChangeEvent<ReadinessState> event) {
        if (this.properties.getStartupMode() != StartupMode.AFTER_READY || !this.properties.isRunOnStartup()
                || event.getState() != ReadinessState.ACCEPTING_TRAFFIC
                || !this.afterReadyStarted.compareAndSet(false, true)) {
            return;
        }
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        this.executor.execute(this::runAfterReady);
    }

    /**
     * Stops the {@code after-ready} migration thread.
     */
    @Override
    public void destroy() {
        ExecutorService current = this.executor;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /**
     * Runs the {@code after-ready} phases. With {@code fail-on-error=true}, a failure makes the application refuse
     * traffic, because startup can no longer be aborted.
     */
    void runAfterReady() {
        try {
            runPhases(snapshot());
        } catch (RuntimeException e) {
            LOGGER.error("Startup migrations failed: {}", e.getMessage(), e);
            if (this.properties.isFailOnError()) {
                AvailabilityChangeEvent.publish(this.eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
            }
        }
    }

    private void runPhases(Map<String, TenantDatabaseProperties> tenants) {
        long start = System.currentTimeMillis();
        LOGGER.info("Running Liquibase migrations for {} tenant(s)", tenants.size());
        try (MigrationSession session = this.resolver.openSession()) {
            Set<String> failedDatabases = this.infrastructureRunner.runInfrastructureMigrations(tenants, session);
            Set<String> failedTenants = this.tenantRunner.migrateAllTenants(tenants, failedDatabases, session);
            if (failedDatabases.isEmpty() && failedTenants.isEmpty()) {
                LOGGER.info("Liquibase migrations completed in {} ms", System.currentTimeMillis() - start);
            } else {
                LOGGER.warn("Liquibase migrations completed in a degraded state; failed databases: {}, failed or "
                        + "skipped tenants: {}", failedDatabases, failedTenants);
            }
        }
    }

    /**
     * Copies the tenants, so that concurrent runtime onboarding cannot disturb iteration. Only the default tenant
     * has a pool when multitenancy is disabled.
     */
    private Map<String, TenantDatabaseProperties> snapshot() {
        for (int attempt = 1; ; attempt++) {
            try {
                Map<String, TenantDatabaseProperties> copy = new TreeMap<>(this.tenantConfigMap);
                if (!this.multitenancyEnabled) {
                    copy.keySet().retainAll(Set.of(MultitenantConstants.DEFAULT_TENANT_ID));
                }
                return Collections.unmodifiableMap(copy);
            } catch (ConcurrentModificationException e) {
                if (attempt >= SNAPSHOT_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }
}
