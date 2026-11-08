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

import com.zaxxer.hikari.HikariDataSource;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One migration run (the startup run, or one runtime onboarding) and the admin pools it created.
 *
 * <p>Admin pools are created lazily and closed with the session, so concurrent runs never close each other's
 * pools and no admin pool outlives the run that needed it.
 */
public class MigrationSession implements AutoCloseable {

    private final MigrationDataSourceResolver resolver;
    private final Map<String, HikariDataSource> adminPools = new ConcurrentHashMap<>();

    /**
     * Creates a session; use {@link MigrationDataSourceResolver#openSession()}.
     *
     * @param resolver the owning resolver
     */
    MigrationSession(MigrationDataSourceResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Resolves the datasource that migrates a tenant (Phases 2 and 3, rollback).
     *
     * @param tenantId the tenant identifier
     * @param tenantProperties the tenant properties
     * @param tenantPool the tenant's own pool, may be {@code null} if it failed to initialize
     * @return an admin pool or the tenant pool
     * @throws MigrationException with {@link MigrationErrorCode#MIGRATION_FAILED} if no datasource applies
     */
    public DataSource resolve(String tenantId, TenantDatabaseProperties tenantProperties, DataSource tenantPool) {
        return this.resolver.resolveAdminAlias(tenantId, tenantProperties)
                .<DataSource>map(this::adminPool)
                .orElseGet(() -> requireTenantPool(tenantId, tenantPool));
    }

    /**
     * Resolves the admin pool that runs Phase 1 for a database.
     *
     * @param target the database
     * @return the admin pool
     * @throws MigrationException with {@link MigrationErrorCode#INVALID_CONFIGURATION} if no admin alias resolves
     */
    public DataSource resolveForDatabase(DatabaseTarget target) {
        if (target.adminAlias() == null) {
            throw new MigrationException("No admin alias resolves for database " + target.key()
                    + "; Phase 1 never runs on a tenant pool", null, MigrationErrorCode.INVALID_CONFIGURATION);
        }
        return adminPool(target.adminAlias());
    }

    /**
     * Closes the admin pools created by this session.
     */
    @Override
    public void close() {
        this.adminPools.values().forEach(HikariDataSource::close);
        this.adminPools.clear();
        this.resolver.sessionClosed(this);
    }

    private HikariDataSource adminPool(String alias) {
        return this.adminPools.computeIfAbsent(alias, this.resolver::createAdminPool);
    }

    private static DataSource requireTenantPool(String tenantId, DataSource tenantPool) {
        if (tenantPool == null) {
            throw new MigrationException("Tenant " + tenantId + " has no datasource to migrate with, because its "
                    + "datasource failed to initialize", tenantId, MigrationErrorCode.MIGRATION_FAILED);
        }
        return tenantPool;
    }
}
