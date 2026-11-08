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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.ecsp.sql.authentication.CredentialsProvider;
import org.eclipse.ecsp.sql.dao.utils.SqlDaoUtils;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the connection that migrates each tenant and each database.
 *
 * <p>Precedence for a tenant: explicit {@code migration-admin}, then an admin alias whose normalized JDBC URL
 * matches (only for tenants that declare a schema), then the tenant's own pool. Phase 1 always runs on an admin
 * pool. Admin pools are short-lived and owned by a {@link MigrationSession}; tenant pools are never closed here.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class MigrationDataSourceResolver implements DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationDataSourceResolver.class);
    private static final String ADMIN_POOL_PREFIX = "sqldao-migration-admin-";

    private final SqlDaoLiquibaseProperties properties;
    private final SqlDaoUtils utils;
    private final Set<MigrationSession> openSessions = ConcurrentHashMap.newKeySet();

    /**
     * Creates the resolver.
     *
     * @param properties the Liquibase migration properties
     * @param utils utility used to instantiate admin credentials providers
     */
    public MigrationDataSourceResolver(SqlDaoLiquibaseProperties properties, SqlDaoUtils utils) {
        this.properties = properties;
        this.utils = utils;
    }

    /**
     * Opens a session that owns the admin pools it creates. Close it when the migration run is over.
     *
     * @return a new session
     */
    public MigrationSession openSession() {
        MigrationSession session = new MigrationSession(this);
        this.openSessions.add(session);
        return session;
    }

    /**
     * Resolves the admin alias for a tenant's Phase 2 and 3 migration, if any applies.
     *
     * @param tenantId the tenant identifier
     * @param tenantProperties the tenant properties
     * @return the explicit or URL-matched admin alias, or empty to use the tenant pool
     */
    public Optional<String> resolveAdminAlias(String tenantId, TenantDatabaseProperties tenantProperties) {
        if (StringUtils.isNotBlank(tenantProperties.getMigrationAdmin())) {
            return Optional.of(tenantProperties.getMigrationAdmin().trim());
        }
        if (tenantProperties.getSchema() == null) {
            return Optional.empty();
        }
        return findAliasByUrl(tenantProperties.getJdbcUrl());
    }

    /**
     * Finds the admin alias whose normalized JDBC URL equals the given URL's.
     *
     * @param jdbcUrl the JDBC URL to match
     * @return the alphabetically first matching alias, or empty
     */
    public Optional<String> findAliasByUrl(String jdbcUrl) {
        String key = JdbcUrlNormalizer.normalize(jdbcUrl);
        return this.properties.getAdmin().entrySet().stream()
                .filter(entry -> key.equals(JdbcUrlNormalizer.normalize(entry.getValue().getJdbcUrl())))
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst();
    }

    /**
     * Groups tenants by normalized JDBC URL and resolves the Phase 1 admin alias of each database: the single
     * explicit {@code migration-admin} named by its tenants, otherwise the URL-matched alias.
     *
     * @param tenants the tenants by identifier
     * @return the distinct databases, sorted by key
     */
    public List<DatabaseTarget> distinctDatabases(Map<String, TenantDatabaseProperties> tenants) {
        Map<String, List<Map.Entry<String, TenantDatabaseProperties>>> byDatabase = new TreeMap<>();
        new TreeMap<>(tenants).entrySet().forEach(entry -> byDatabase
                .computeIfAbsent(JdbcUrlNormalizer.normalize(entry.getValue().getJdbcUrl()), key -> new ArrayList<>())
                .add(entry));
        List<DatabaseTarget> targets = new ArrayList<>();
        byDatabase.forEach((key, entries) -> targets.add(toTarget(key, entries)));
        return targets;
    }

    /**
     * Returns the database of a single tenant, used for Phase 1 during runtime onboarding.
     *
     * @param tenantId the tenant identifier
     * @param tenantProperties the tenant properties
     * @return the tenant's database
     */
    public DatabaseTarget databaseOf(String tenantId, TenantDatabaseProperties tenantProperties) {
        return distinctDatabases(Map.of(tenantId, tenantProperties)).get(0);
    }

    private DatabaseTarget toTarget(String key, List<Map.Entry<String, TenantDatabaseProperties>> entries) {
        List<String> tenantIds = entries.stream().map(Map.Entry::getKey).toList();
        Set<String> explicitAliases = new TreeSet<>();
        entries.stream()
                .map(entry -> entry.getValue().getMigrationAdmin())
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .forEach(explicitAliases::add);
        String jdbcUrl = entries.get(0).getValue().getJdbcUrl();
        String adminAlias;
        if (explicitAliases.size() == 1) {
            adminAlias = explicitAliases.iterator().next();
        } else if (explicitAliases.isEmpty()) {
            adminAlias = findAliasByUrl(jdbcUrl).orElse(null);
        } else {
            adminAlias = null;
        }
        return new DatabaseTarget(key, jdbcUrl, tenantIds, Set.copyOf(explicitAliases), adminAlias);
    }

    /**
     * Creates an admin pool. Credentials are read now, so rotated vault credentials are picked up by the next
     * session without a refresh job.
     *
     * @param alias the admin alias
     * @return a new admin pool
     */
    HikariDataSource createAdminPool(String alias) {
        AdminDataSourceProperties admin = this.properties.getAdmin().get(alias);
        if (admin == null) {
            throw new MigrationException("Unknown admin alias '" + alias + "' (sqldao.liquibase.admin." + alias
                    + ".jdbc-url is not set)", null, MigrationErrorCode.INVALID_CONFIGURATION);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(admin.getJdbcUrl());
        config.setDriverClassName(admin.getDriverClassName());
        config.setMinimumIdle(admin.getMinPoolSize());
        config.setMaximumPoolSize(admin.getMaxPoolSize());
        config.setPoolName(ADMIN_POOL_PREFIX + alias);
        applyCredentials(config, alias, admin);
        try {
            HikariDataSource pool = new HikariDataSource(config);
            LOGGER.info("Created migration admin pool for alias {}", alias);
            return pool;
        } catch (RuntimeException e) {
            throw new MigrationException("Failed to create the migration admin pool for alias '" + alias + "'",
                    null, MigrationErrorCode.MIGRATION_FAILED, e);
        }
    }

    private void applyCredentials(HikariConfig config, String alias, AdminDataSourceProperties admin) {
        if (StringUtils.isBlank(admin.getCredentialProviderBeanName())) {
            config.setUsername(admin.getUserName());
            config.setPassword(admin.getPassword());
            return;
        }
        try {
            CredentialsProvider provider =
                    (CredentialsProvider) this.utils.getClassInstance(admin.getCredentialProviderBeanName().trim());
            provider.refreshCredentials();
            config.setUsername(provider.getUserName());
            config.setPassword(provider.getPassword());
        } catch (RuntimeException e) {
            throw new MigrationException("Failed to load admin credentials for alias '" + alias + "'",
                    null, MigrationErrorCode.MIGRATION_FAILED, e);
        }
    }

    /**
     * Called by a session when it closes.
     *
     * @param session the closed session
     */
    void sessionClosed(MigrationSession session) {
        this.openSessions.remove(session);
    }

    /**
     * Closes admin pools of sessions that are still open at shutdown.
     */
    @Override
    public void destroy() {
        new ArrayList<>(this.openSessions).forEach(MigrationSession::close);
    }
}
