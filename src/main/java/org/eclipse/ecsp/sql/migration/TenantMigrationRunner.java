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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phases 2 and 3: provisions (opt-in) and migrates each tenant schema under its per-schema advisory lock.
 *
 * <p>An in-JVM per-tenant lock ensures that startup migration, runtime onboarding, and removal of the same tenant
 * never overlap; across pods the advisory lock does the same.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class TenantMigrationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantMigrationRunner.class);

    private final SqlDaoLiquibaseProperties properties;
    private final MigrationDataSourceResolver resolver;
    private final SchemaMigrationLock schemaLock;
    private final SchemaProvisioningService provisioningService;
    private final LiquibaseMigrationService liquibaseService;
    private final InfrastructureMigrationRunner infrastructureRunner;
    private final MigrationConfigurationValidator validator;
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
     * @param targetDataSources the tenant pools by tenant identifier
     */
    public TenantMigrationRunner(SqlDaoLiquibaseProperties properties, MigrationDataSourceResolver resolver,
            SchemaMigrationLock schemaLock, SchemaProvisioningService provisioningService,
            LiquibaseMigrationService liquibaseService, InfrastructureMigrationRunner infrastructureRunner,
            MigrationConfigurationValidator validator,
            @Qualifier("targetDataSources") Map<String, DataSource> targetDataSources) {
        this.properties = properties;
        this.resolver = resolver;
        this.schemaLock = schemaLock;
        this.provisioningService = provisioningService;
        this.liquibaseService = liquibaseService;
        this.infrastructureRunner = infrastructureRunner;
        this.validator = validator;
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
     * Migrates every tenant, in tenant-ID order. Tenants whose database failed Phase 1 are skipped, and tenants
     * that declare the same schema in the same database are migrated once.
     *
     * @param tenants the tenants by identifier
     * @param skipDatabases keys of databases whose Phase 1 failed
     * @param session the migration session that owns the admin pools
     * @return identifiers of tenants that failed or were skipped (only with {@code fail-on-error=false})
     * @throws MigrationException on the first failure when {@code fail-on-error=true}
     */
    public Set<String> migrateAllTenants(Map<String, TenantDatabaseProperties> tenants, Set<String> skipDatabases,
            MigrationSession session) {
        Set<String> failedTenants = new TreeSet<>();
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
            } else if (!migrateUnderTenantLock(tenantId, tenant, session)) {
                failedTenants.add(tenantId);
            }
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
        DataSource dataSource = session.resolve(tenantId, tenant, tenantPool);
        String schema = tenant.getSchema();
        this.schemaLock.withLockedConnection(dataSource, tenantId, schema, this.properties.getLockTimeoutSeconds(),
                connection -> {
                    if (this.properties.isProvisionSchemas() && schema != null) {
                        this.provisioningService.ensureSchema(connection, tenantId, schema);
                    }
                    this.liquibaseService.runMigrations(connection, tenantId, schema, this.properties.getChangeLog());
                });
        LOGGER.info("Migrated tenant {}", tenantId);
    }
}
