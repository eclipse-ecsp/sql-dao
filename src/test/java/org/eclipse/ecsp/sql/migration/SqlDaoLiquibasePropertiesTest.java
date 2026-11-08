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

    @Test
    void hasDocumentedDefaults() {
        SqlDaoLiquibaseProperties properties = new SqlDaoLiquibaseProperties();
        assertFalse(properties.isEnabled());
        assertNull(properties.getChangeLog());
        assertNull(properties.getPublicChangeLog());
        assertEquals("public", properties.getPublicSchema());
        assertTrue(properties.isRunOnStartup());
        assertFalse(properties.isProvisionSchemas());
        assertEquals(StartupMode.BLOCKING, properties.getStartupMode());
        assertTrue(properties.isFailOnError());
        assertEquals(SqlDaoLiquibaseProperties.DEFAULT_LOCK_TIMEOUT_SECONDS, properties.getLockTimeoutSeconds());
        assertTrue(properties.isReleaseStaleLocks());
        assertEquals(SqlDaoLiquibaseProperties.DEFAULT_CHANGELOG_LOCK_WAIT_MINUTES,
                properties.getChangelogLockWaitMinutes());
        assertTrue(properties.getAdmin().isEmpty());
    }

    @Test
    void bindsKebabCasePropertiesAndAdminAliases() {
        Map<String, String> source = Map.of(
                "sqldao.liquibase.enabled", "true",
                "sqldao.liquibase.change-log", "classpath:db/changelog/db.changelog-tenant.xml",
                "sqldao.liquibase.startup-mode", "after-ready",
                "sqldao.liquibase.lock-timeout-seconds", String.valueOf(LOCK_TIMEOUT),
                "sqldao.liquibase.release-stale-locks", "false",
                "sqldao.liquibase.changelog-lock-wait-minutes", String.valueOf(LOCK_WAIT),
                "sqldao.liquibase.admin.appdb.jdbc-url", "jdbc:postgresql://db-host:5432/appdb",
                "sqldao.liquibase.admin.appdb.user-name", "admin",
                "sqldao.liquibase.admin.appdb.credential-provider-bean-name", "com.example.Vault");
        SqlDaoLiquibaseProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("sqldao.liquibase", SqlDaoLiquibaseProperties.class).get();
        assertTrue(properties.isEnabled());
        assertEquals(StartupMode.AFTER_READY, properties.getStartupMode());
        assertEquals(LOCK_TIMEOUT, properties.getLockTimeoutSeconds());
        assertFalse(properties.isReleaseStaleLocks());
        assertEquals(LOCK_WAIT, properties.getChangelogLockWaitMinutes());
        AdminDataSourceProperties admin = properties.getAdmin().get("appdb");
        assertEquals("jdbc:postgresql://db-host:5432/appdb", admin.getJdbcUrl());
        assertEquals("admin", admin.getUserName());
        assertEquals("com.example.Vault", admin.getCredentialProviderBeanName());
        assertEquals("org.postgresql.Driver", admin.getDriverClassName());
        assertEquals(0, admin.getMinPoolSize());
        assertEquals(AdminDataSourceProperties.DEFAULT_MAX_POOL_SIZE, admin.getMaxPoolSize());
    }

    @Test
    void bindsTenantMigrationAdmin() {
        Map<String, String> source = Map.of("tenants.profile.tenant1.migration-admin", "ordersdb",
                "tenants.profile.tenant1.schema", "orders_eu");
        TenantDatabaseProperties tenant = new Binder(new MapConfigurationPropertySource(source))
                .bind("tenants.profile", Bindable.mapOf(String.class, TenantDatabaseProperties.class)).get()
                .get("tenant1");
        assertEquals("ordersdb", tenant.getMigrationAdmin());
        assertEquals("orders_eu", tenant.getSchema());
    }
}
