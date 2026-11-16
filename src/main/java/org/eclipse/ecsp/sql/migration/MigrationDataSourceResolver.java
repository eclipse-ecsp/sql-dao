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
import org.eclipse.ecsp.sql.migration.MigrationException.MigrationErrorCode;
import org.eclipse.ecsp.sql.migration.SqlDaoLiquibaseProperties.AdminDataSourceProperties;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Resolves the connection that migrates each tenant and each database.
 *
 * <p>For a tenant, an admin alias whose normalized JDBC URL matches is used only when the tenant declares a schema;
 * otherwise migration uses the tenant's own pool. Phase 1 always runs on a URL-matched admin pool. Admin pools are
 * short-lived and owned by a {@link MigrationSession}; tenant pools are never closed here.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class MigrationDataSourceResolver implements DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationDataSourceResolver.class);
    private static final String ADMIN_POOL_PREFIX = "sqldao-migration-admin-";
    private static final String POSTGRES_PREFIX = "jdbc:postgresql:";
    private static final String AUTHORITY_PREFIX = "//";
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "5432";

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
     * @param tenantProperties the tenant properties
     * @return the URL-matched admin alias, or empty to use the tenant pool
     */
    public Optional<String> resolveAdminAlias(TenantDatabaseProperties tenantProperties) {
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
        String key = normalizeJdbcUrl(jdbcUrl);
        return this.properties.getAdmin().entrySet().stream()
                .filter(entry -> key.equals(normalizeJdbcUrl(entry.getValue().getJdbcUrl())))
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst();
    }

    /**
     * Groups tenants by normalized JDBC URL and resolves the Phase 1 admin alias by matching the database URL.
     *
     * @param tenants the tenants by identifier
     * @return the distinct databases, sorted by key
     */
    public List<DatabaseTarget> distinctDatabases(Map<String, TenantDatabaseProperties> tenants) {
        Map<String, List<Map.Entry<String, TenantDatabaseProperties>>> byDatabase = new TreeMap<>();
        new TreeMap<>(tenants).entrySet().forEach(entry -> byDatabase
                .computeIfAbsent(normalizeJdbcUrl(entry.getValue().getJdbcUrl()), key -> new ArrayList<>())
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
        String jdbcUrl = entries.get(0).getValue().getJdbcUrl();
        String adminAlias = findAliasByUrl(jdbcUrl).orElse(null);
        return new DatabaseTarget(key, jdbcUrl, tenantIds, adminAlias);
    }

    /**
     * Creates an admin pool. Credentials are read now, so rotated vault credentials are picked up by the next
     * session without a refresh job.
     *
     * @param alias the admin alias
     * @return a new admin pool
     */
    HikariDataSource createAdminPool(String alias) {
        HikariConfig config = adminPoolConfig(alias);
        try {
            HikariDataSource pool = new HikariDataSource(config);
            LOGGER.info("Created migration admin pool for alias {} (max {} connections)", alias,
                    config.getMaximumPoolSize());
            return pool;
        } catch (RuntimeException e) {
            throw new MigrationException("Failed to create the migration admin pool for alias '" + alias + "'",
                    null, MigrationErrorCode.MIGRATION_FAILED, e);
        }
    }

    HikariConfig adminPoolConfig(String alias) {
        AdminDataSourceProperties admin = this.properties.getAdmin().get(alias);
        if (admin == null) {
            throw new MigrationException("Unknown admin alias '" + alias + "' (sqldao.liquibase.admin." + alias
                    + ".jdbc-url is not set)", null, MigrationErrorCode.INVALID_CONFIGURATION);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(admin.getJdbcUrl());
        config.setDriverClassName(admin.getDriverClassName());
        config.setMinimumIdle(admin.getMinPoolSize());
        // Every parallel migration holds one admin connection for its whole run; a smaller pool would make
        // workers queue and fail with a connection timeout.
        config.setMaximumPoolSize(Math.max(admin.getMaxPoolSize(), this.properties.getParallelism()));
        config.setPoolName(ADMIN_POOL_PREFIX + alias);
        applyCredentials(config, alias, admin);
        return config;
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
     * Reduces a PostgreSQL JDBC URL to {@code host:port/database}, so that tenants of one database can be grouped.
     *
     * <p>Query parameters are stripped, hosts are lower-cased, a missing port defaults to {@code 5432}, and
     * multi-host URLs use the sorted host list. De-duplication is an optimization: two URLs for the same database
     * that normalize differently only cause a second, no-op Phase 1 run.
     *
     *
     * @param jdbcUrl the JDBC URL, may be {@code null}
     * @return {@code host:port[,host:port...]/database}, or an empty string for a blank URL
     */
    public static String normalizeJdbcUrl(String jdbcUrl) {
        if (StringUtils.isBlank(jdbcUrl)) {
            return "";
        }
        String url = StringUtils.substringBefore(jdbcUrl.trim(), "?");
        if (!url.regionMatches(true, 0, POSTGRES_PREFIX, 0, POSTGRES_PREFIX.length())) {
            return url.toLowerCase(Locale.ROOT);
        }
        String rest = url.substring(POSTGRES_PREFIX.length());
        if (!rest.startsWith(AUTHORITY_PREFIX)) {
            return DEFAULT_HOST + ":" + DEFAULT_PORT + "/" + StringUtils.removeStart(rest, "/");
        }
        String authority = rest.substring(AUTHORITY_PREFIX.length());
        String hosts = StringUtils.substringBefore(authority, "/");
        String database = authority.contains("/") ? StringUtils.substringAfter(authority, "/") : "";
        return normalizeHosts(hosts) + "/" + database;
    }

    private static String normalizeHosts(String hosts) {
        String normalized = Arrays.stream(hosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(MigrationDataSourceResolver::normalizeHost)
                .sorted()
                .collect(Collectors.joining(","));
        return normalized.isEmpty() ? DEFAULT_HOST + ":" + DEFAULT_PORT : normalized;
    }

    private static String normalizeHost(String hostAndPort) {
        String value = hostAndPort.toLowerCase(Locale.ROOT);
        // IPv6 hosts are bracketed; look for the port separator after the closing bracket.
        int searchFrom = value.startsWith("[") ? Math.max(value.indexOf(']'), 0) : 0;
        int portSeparator = value.indexOf(':', searchFrom);
        if (portSeparator < 0) {
            return value + ":" + DEFAULT_PORT;
        }
        String host = value.substring(0, portSeparator);
        String port = value.substring(portSeparator + 1);
        return host + ":" + (port.isEmpty() ? DEFAULT_PORT : port);
    }

    /**
     * Closes admin pools of sessions that are still open at shutdown.
     */
    @Override
    public void destroy() {
        new ArrayList<>(this.openSessions).forEach(MigrationSession::close);
    }

    /**
     * A distinct database that Phase 1 migrates once, with the tenants that live in it.
     *
     * @param key normalized JDBC URL ({@code host:port/database})
     * @param jdbcUrl JDBC URL of one of its tenants
     * @param tenantIds tenants in this database, sorted
     * @param adminAlias admin alias resolved for Phase 1, or {@code null} when none resolves
     */
    public record DatabaseTarget(String key, String jdbcUrl, List<String> tenantIds,
                                 String adminAlias) {
    }

    /**
     * One migration run (the startup run, or one runtime onboarding) and the admin pools it created.
     *
     * <p>Admin pools are created lazily and closed with the session, so concurrent runs never close each other's
     * pools and no admin pool outlives the run that needed it.
     */
    public static class MigrationSession implements AutoCloseable {

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
            return this.resolver.resolveAdminAlias(tenantProperties)
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
}
