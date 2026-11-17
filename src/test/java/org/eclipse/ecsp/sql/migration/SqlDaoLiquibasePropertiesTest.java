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

import org.eclipse.ecsp.sql.migration.SqlDaoLiquibaseProperties.AdminDataSourceProperties;
import org.eclipse.ecsp.sql.migration.SqlDaoLiquibaseProperties.MigrationRetryProperties;
import org.eclipse.ecsp.sql.migration.SqlDaoLiquibaseProperties.StartupMode;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SqlDaoLiquibaseProperties} defaults and binding.
 */
class SqlDaoLiquibasePropertiesTest {

    private static final int LOCK_TIMEOUT = 120;
    private static final int LOCK_WAIT = 7;
    private static final int PARALLELISM = 4;
    private static final int RETRY_ATTEMPTS = 5;
    private static final long RETRY_DELAY = 250L;

    @Test
    void hasDocumentedDefaults() {
        SqlDaoLiquibaseProperties properties = new SqlDaoLiquibaseProperties();
        assertFalse(properties.isEnabled());
        assertNull(properties.getChangeLog());
        assertNull(properties.getPublicChangeLog());
        assertEquals("public", properties.getPublicSchema());
        assertTrue(properties.isRunOnStartup());
        assertEquals(StartupMode.BLOCKING, properties.getStartupMode());
        assertEquals(SqlDaoLiquibaseProperties.DEFAULT_LOCK_TIMEOUT_SECONDS, properties.getLockTimeoutSeconds());
        assertEquals(1, properties.getParallelism());
        assertTrue(properties.isReleaseStaleLocks());
        assertEquals(SqlDaoLiquibaseProperties.DEFAULT_CHANGELOG_LOCK_WAIT_MINUTES,
                properties.getChangelogLockWaitMinutes());
        assertTrue(properties.getAdmin().isEmpty());
        assertEquals(MigrationRetryProperties.DEFAULT_MAX_ATTEMPTS, properties.getRetry().getMaxAttempts());
        assertEquals(MigrationRetryProperties.DEFAULT_DELAY_MS, properties.getRetry().getDelayMs());
    }

    @Test
    void bindsKebabCasePropertiesAndAdminAliases() {
        Map<String, String> source = Map.ofEntries(
                Map.entry("sqldao.liquibase.enabled", "true"),
                Map.entry("sqldao.liquibase.change-log", "classpath:db/changelog/db.changelog-tenant.xml"),
                Map.entry("sqldao.liquibase.startup-mode", "after-ready"),
                Map.entry("sqldao.liquibase.lock-timeout-seconds", String.valueOf(LOCK_TIMEOUT)),
                Map.entry("sqldao.liquibase.release-stale-locks", "false"),
                Map.entry("sqldao.liquibase.changelog-lock-wait-minutes", String.valueOf(LOCK_WAIT)),
                Map.entry("sqldao.liquibase.parallelism", String.valueOf(PARALLELISM)),
                Map.entry("sqldao.liquibase.retry.max-attempts", String.valueOf(RETRY_ATTEMPTS)),
                Map.entry("sqldao.liquibase.retry.delay-ms", String.valueOf(RETRY_DELAY)),
                Map.entry("sqldao.liquibase.admin.appdb.jdbc-url", "jdbc:postgresql://db-host:5432/appdb"),
                Map.entry("sqldao.liquibase.admin.appdb.user-name", "admin"),
                Map.entry("sqldao.liquibase.admin.appdb.credential-provider-bean-name", "com.example.Vault"));
        SqlDaoLiquibaseProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("sqldao.liquibase", SqlDaoLiquibaseProperties.class).get();
        assertTrue(properties.isEnabled());
        assertEquals(StartupMode.AFTER_READY, properties.getStartupMode());
        assertEquals(LOCK_TIMEOUT, properties.getLockTimeoutSeconds());
        assertFalse(properties.isReleaseStaleLocks());
        assertEquals(LOCK_WAIT, properties.getChangelogLockWaitMinutes());
        assertEquals(PARALLELISM, properties.getParallelism());
        assertEquals(RETRY_ATTEMPTS, properties.getRetry().getMaxAttempts());
        assertEquals(RETRY_DELAY, properties.getRetry().getDelayMs());
        AdminDataSourceProperties admin = properties.getAdmin().get("appdb");
        assertEquals("jdbc:postgresql://db-host:5432/appdb", admin.getJdbcUrl());
        assertEquals("admin", admin.getUserName());
        assertEquals("com.example.Vault", admin.getCredentialProviderBeanName());
        assertEquals("org.postgresql.Driver", admin.getDriverClassName());
        assertEquals(0, admin.getMinPoolSize());
        assertEquals(AdminDataSourceProperties.DEFAULT_MAX_POOL_SIZE, admin.getMaxPoolSize());
    }

}
