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
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase 1: applies the public changelog to the shared schema once per distinct database, on its admin pool.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class InfrastructureMigrationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(InfrastructureMigrationRunner.class);

    private final SqlDaoLiquibaseProperties properties;
    private final MigrationDataSourceResolver resolver;
    private final SchemaMigrationLock schemaLock;
    private final LiquibaseMigrationService liquibaseService;

    /**
     * Creates the runner.
     *
     * @param properties the Liquibase migration properties
     * @param resolver the migration datasource resolver
     * @param schemaLock the per-schema advisory lock
     * @param liquibaseService the Liquibase command runner
     */
    public InfrastructureMigrationRunner(SqlDaoLiquibaseProperties properties, MigrationDataSourceResolver resolver,
            SchemaMigrationLock schemaLock, LiquibaseMigrationService liquibaseService) {
        this.properties = properties;
        this.resolver = resolver;
        this.schemaLock = schemaLock;
        this.liquibaseService = liquibaseService;
    }

    /**
     * Runs Phase 1 for every distinct database of the given tenants. Skipped when no public changelog is set.
     *
     * @param tenants the tenants by identifier
     * @param session the migration session that owns the admin pools
     * @return keys of databases whose Phase 1 failed (only with {@code fail-on-error=false})
     * @throws MigrationException on the first failure when {@code fail-on-error=true}
     */
    public Set<String> runInfrastructureMigrations(Map<String, TenantDatabaseProperties> tenants,
            MigrationSession session) {
        Set<String> failedDatabases = new TreeSet<>();
        if (StringUtils.isBlank(this.properties.getPublicChangeLog())) {
            LOGGER.info("No sqldao.liquibase.public-change-log set; skipping Phase 1");
            return failedDatabases;
        }
        for (DatabaseTarget target : this.resolver.distinctDatabases(tenants)) {
            if (!migrateDatabase(target, session)) {
                failedDatabases.add(target.key());
            }
        }
        return failedDatabases;
    }

    /**
     * Runs Phase 1 for one database under the shared schema's advisory lock.
     *
     * @param target the database
     * @param session the migration session that owns the admin pools
     * @return {@code true} on success, {@code false} on failure with {@code fail-on-error=false}
     * @throws MigrationException on failure when {@code fail-on-error=true}
     */
    public boolean migrateDatabase(DatabaseTarget target, MigrationSession session) {
        String publicSchema = this.properties.getPublicSchema();
        try {
            DataSource adminPool = session.resolveForDatabase(target);
            this.schemaLock.withLockedConnection(adminPool, null, publicSchema, this.properties.getLockTimeoutSeconds(),
                    connection -> this.liquibaseService.runPublicMigrations(connection, publicSchema,
                            this.properties.getPublicChangeLog()));
            LOGGER.info("Phase 1 completed for database {}", target.key());
            return true;
        } catch (MigrationException e) {
            if (this.properties.isFailOnError()) {
                throw e;
            }
            LOGGER.error("Phase 1 failed for database {}; tenants {} will not be migrated: {}", target.key(),
                    target.tenantIds(), e.getMessage(), e);
            return false;
        }
    }
}
