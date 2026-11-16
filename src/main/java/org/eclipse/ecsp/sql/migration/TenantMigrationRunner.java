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

import org.apache.commons.lang3.StringUtils;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phases 2 and 3: provisions (opt-in) and migrates each tenant schema under its per-schema advisory lock.
 *
 * <p>An in-JVM per-tenant lock ensures that startup migration, runtime onboarding, and removal of the same tenant
 * never overlap; across pods the advisory lock does the same.
 *
 * <p>At startup, tenants are migrated one at a time, or by {@code sqldao.liquibase.parallelism} workers when that is
 * greater than one. Parallel workers never share a schema: tenants that declare the same schema are migrated once,
 * and the per-schema advisory lock serializes any that still collide.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class TenantMigrationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantMigrationRunner.class);
    private static final String WORKER_THREAD_PREFIX = "sqldao-liquibase-tenant-";

    private final SqlDaoLiquibaseProperties properties;
    private final MigrationDataSourceResolver resolver;
    private final SchemaMigrationLock schemaLock;
    private final SchemaProvisioningService provisioningService;
    private final LiquibaseMigrationService liquibaseService;
    private final InfrastructureMigrationRunner infrastructureRunner;
    private final MigrationConfigurationValidator validator;
    private final MigrationRetryExecutor retryExecutor;
    private final Map<String, DataSource> targetDataSources;
    private final ConcurrentMap<String, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();

    /**
     * Creates the runner.
     *
     * @param properties the Liquibase migration properties
     * @param resolver the migration datasource resolver
     * @param schemaLock the per-schema advisory lock
     * @param provisioningService the schema provisioning service
     * @param liquibaseService the Liquibase command runner
     * @param infrastructureRunner the Phase 1 runner, used for runtime onboarding
     * @param validator the configuration validator
     * @param retryExecutor retries an attempt that failed
     * @param targetDataSources the tenant pools by tenant identifier
     */
    public TenantMigrationRunner(SqlDaoLiquibaseProperties properties, MigrationDataSourceResolver resolver,
            SchemaMigrationLock schemaLock, SchemaProvisioningService provisioningService,
            LiquibaseMigrationService liquibaseService, InfrastructureMigrationRunner infrastructureRunner,
            MigrationConfigurationValidator validator, MigrationRetryExecutor retryExecutor,
            @Qualifier("targetDataSources") Map<String, DataSource> targetDataSources) {
        this.properties = properties;
        this.resolver = resolver;
        this.schemaLock = schemaLock;
        this.provisioningService = provisioningService;
        this.liquibaseService = liquibaseService;
        this.infrastructureRunner = infrastructureRunner;
        this.validator = validator;
        this.retryExecutor = retryExecutor;
        this.targetDataSources = targetDataSources;
    }

    /**
     * Returns the in-JVM lock of a tenant.
     *
     * @param tenantId the tenant identifier
     * @return the tenant's reentrant lock
     */
    public Lock tenantLock(String tenantId) {
        return this.tenantLocks.computeIfAbsent(tenantId, id -> new ReentrantLock());
    }

    /**
     * Whether a migration failure must abort startup or a runtime onboarding.
     *
     * @return the {@code fail-on-error} setting
     */
    public boolean isFailOnError() {
        return this.properties.isFailOnError();
    }

    /**
     * Validates one tenant's migration configuration.
     *
     * @param tenantId the tenant identifier
     * @param tenant the tenant properties
     * @return the problems found, empty when valid
     */
    public List<String> validateTenant(String tenantId, TenantDatabaseProperties tenant) {
        return this.validator.validateTenant(tenantId, tenant);
    }

    /**
     * Migrates every tenant, in tenant-ID order when sequential. Tenants whose database failed Phase 1 are skipped,
     * and tenants that declare the same schema in the same database are migrated once. With
     * {@code sqldao.liquibase.parallelism} above one, up to that many tenants are migrated at the same time, and
     * this method returns only after every started migration has finished.
     *
     * @param tenants the tenants by identifier
     * @param skipDatabases keys of databases whose Phase 1 failed
     * @param session the migration session that owns the admin pools
     * @return identifiers of tenants that failed or were skipped (only with {@code fail-on-error=false})
     * @throws MigrationException on the first failure when {@code fail-on-error=true}; tenants that have not started
     *         by then are not migrated, and failures of migrations already running are added as suppressed
     */
    public Set<String> migrateAllTenants(Map<String, TenantDatabaseProperties> tenants, Set<String> skipDatabases,
            MigrationSession session) {
        Set<String> failedTenants = new TreeSet<>();
        Map<String, TenantDatabaseProperties> toMigrate = selectTenants(tenants, skipDatabases, failedTenants);
        int workers = Math.min(this.properties.getParallelism(), toMigrate.size());
        if (workers <= 1) {
            toMigrate.forEach((tenantId, tenant) -> {
                if (!migrateUnderTenantLock(tenantId, tenant, session)) {
                    failedTenants.add(tenantId);
                }
            });
        } else {
            failedTenants.addAll(migrateInParallel(toMigrate, workers, session));
        }
        return failedTenants;
    }

    /**
     * Migrates a tenant that is being onboarded at runtime, before its route is published. Runs Phase 1 for its
     * database first when a public changelog is set. Never throws for migration failures.
     *
     * @param tenantId the tenant identifier
     * @param tenant the tenant properties
     * @param tenantPool the tenant's new, not yet registered pool
     * @return {@code true} if the tenant was migrated
     */
    public boolean migrateTenant(String tenantId, TenantDatabaseProperties tenant, DataSource tenantPool) {
        Lock lock = tenantLock(tenantId);
        lock.lock();
        try (MigrationSession session = this.resolver.openSession()) {
            boolean runPhaseOne = StringUtils.isNotBlank(this.properties.getPublicChangeLog());
            if (runPhaseOne && !this.infrastructureRunner.migrateDatabase(
                    this.resolver.databaseOf(tenantId, tenant), session)) {
                return false;
            }
            migrateSchema(tenantId, tenant, tenantPool, session);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("Migration of tenant {} failed: {}", tenantId, e.getMessage(), e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Rolls back the last {@code count} changesets of a tenant with Liquibase {@code rollbackCount}. Run it while the
     * service is not deploying, so that a starting pod does not immediately re-apply them.
     *
     * @param tenantId the tenant identifier
     * @param tenant the tenant properties
     * @param tenantPool the tenant's pool, used when no admin alias applies
     * @param count number of changesets to roll back
     * @throws MigrationException with {@link MigrationErrorCode#ROLLBACK_FAILED} on failure
     */
    public void rollbackTenant(String tenantId, TenantDatabaseProperties tenant, DataSource tenantPool, int count) {
        Lock lock = tenantLock(tenantId);
        lock.lock();
        try (MigrationSession session = this.resolver.openSession()) {
            DataSource dataSource = session.resolve(tenantId, tenant, tenantPool);
            this.schemaLock.withLockedConnection(dataSource, tenantId, tenant.getSchema(),
                    this.properties.getLockTimeoutSeconds(), connection -> this.liquibaseService
                            .rollbackMigrations(connection, tenantId, tenant.getSchema(), count));
            LOGGER.info("Rolled back {} changeset(s) for tenant {}", count, tenantId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Picks the tenants to migrate, in tenant-ID order. Done before any worker starts, so that skipping and
     * schema de-duplication need no shared mutable state.
     */
    private Map<String, TenantDatabaseProperties> selectTenants(Map<String, TenantDatabaseProperties> tenants,
            Set<String> skipDatabases, Set<String> failedTenants) {
        Map<String, TenantDatabaseProperties> selected = new LinkedHashMap<>();
        Set<String> migratedSchemas = new HashSet<>();
        for (Map.Entry<String, TenantDatabaseProperties> entry : new TreeMap<>(tenants).entrySet()) {
            String tenantId = entry.getKey();
            TenantDatabaseProperties tenant = entry.getValue();
            String databaseKey = JdbcUrlNormalizer.normalize(tenant.getJdbcUrl());
            if (skipDatabases.contains(databaseKey)) {
                LOGGER.warn("Skipping tenant {}: Phase 1 failed for its database {}", tenantId, databaseKey);
                failedTenants.add(tenantId);
            } else if (tenant.getSchema() != null && !migratedSchemas.add(databaseKey + "/" + tenant.getSchema())) {
                LOGGER.warn("Tenant {} uses schema {} of database {}, which was already migrated; skipping", tenantId,
                        tenant.getSchema(), databaseKey);
            } else {
                selected.put(tenantId, tenant);
            }
        }
        return selected;
    }

    private Set<String> migrateInParallel(Map<String, TenantDatabaseProperties> tenants, int workers,
            MigrationSession session) {
        LOGGER.info("Migrating {} tenant(s) with {} parallel workers", tenants.size(), workers);
        Set<String> failedTenants = ConcurrentHashMap.newKeySet();
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, WORKER_THREAD_PREFIX + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            tenants.forEach((tenantId, tenant) -> futures.add(executor.submit(() -> {
                if (aborted.get()) {
                    return;
                }
                try {
                    if (!migrateUnderTenantLock(tenantId, tenant, session)) {
                        failedTenants.add(tenantId);
                    }
                } catch (RuntimeException e) {
                    aborted.set(true);
                    throw e;
                }
            })));
            awaitAll(futures, aborted, executor);
        } finally {
            executor.shutdown();
        }
        return failedTenants;
    }

    /**
     * Waits for every submitted migration, so that no worker still uses the session's pools when the caller closes
     * it. The first failure is rethrown afterwards with the others attached as suppressed.
     */
    private static void awaitAll(List<Future<?>> futures, AtomicBoolean aborted, ExecutorService executor) {
        RuntimeException failure = null;
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                RuntimeException current = e.getCause() instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new MigrationException("Tenant migration failed: " + e.getCause(), null,
                                MigrationErrorCode.MIGRATION_FAILED, e.getCause());
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            } catch (InterruptedException e) {
                aborted.set(true);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new MigrationException("Interrupted while migrating tenants", null,
                        MigrationErrorCode.MIGRATION_FAILED, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean migrateUnderTenantLock(String tenantId, TenantDatabaseProperties tenant,
            MigrationSession session) {
        Lock lock = tenantLock(tenantId);
        lock.lock();
        try {
            migrateSchema(tenantId, tenant, this.targetDataSources.get(tenantId), session);
            return true;
        } catch (MigrationException e) {
            if (this.properties.isFailOnError()) {
                throw e;
            }
            LOGGER.error("Migration of tenant {} failed: {}", tenantId, e.getMessage(), e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void migrateSchema(String tenantId, TenantDatabaseProperties tenant, DataSource tenantPool,
            MigrationSession session) {
        String schema = tenant.getSchema();
        // One attempt resolves the datasource too, because creating an admin pool fails when the database is down.
        this.retryExecutor.run("tenant " + tenantId, () -> {
            DataSource dataSource = session.resolve(tenantId, tenant, tenantPool);
            this.schemaLock.withLockedConnection(dataSource, tenantId, schema,
                    this.properties.getLockTimeoutSeconds(), connection -> {
                        if (schema != null) {
                            this.provisioningService.ensureSchema(connection, tenantId, schema);
                        }
                        this.liquibaseService.runMigrations(connection, tenantId, schema,
                                this.properties.getChangeLog());
                    });
        });
        LOGGER.info("Migrated tenant {}", tenantId);
    }
}
