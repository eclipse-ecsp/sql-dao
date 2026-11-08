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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration checks that always fail startup ({@link MigrationConfigurationValidator}).
 */
class MigrationStartupValidationTest {

    private static final String APP_DB = "jdbc:postgresql://db-host:5432/appdb";

    private SqlDaoLiquibaseProperties properties;
    private MockEnvironment environment;
    private MigrationConfigurationValidator validator;
    private Map<String, TenantDatabaseProperties> tenants;

    @BeforeEach
    void setUp() {
        properties = new SqlDaoLiquibaseProperties();
        properties.setEnabled(true);
        properties.setChangeLog("classpath:db/changelog/db.changelog-tenant.xml");
        environment = new MockEnvironment();
        validator = new MigrationConfigurationValidator(properties, new MigrationDataSourceResolver(properties, null),
                new SchemaProvisioningService(properties), environment);
        tenants = new HashMap<>();
        tenants.put("tenant1", tenant(APP_DB, "tenant1_schema"));
        tenants.put("tenant2", tenant(APP_DB, "tenant2_schema"));
    }

    @Test
    void acceptsValidConfiguration() {
        properties.setPublicChangeLog("classpath:db/changelog/db.changelog-public.xml");
        properties.getAdmin().put("appdb", admin(APP_DB));
        assertDoesNotThrow(() -> validator.validate(tenants));
    }

    @Test
    void requiresChangeLog() {
        properties.setChangeLog(" ");
        assertInvalid("change-log is required");
    }

    @Test
    void rejectsDeferredDatasourceInitialization() {
        environment.setProperty("spring.jpa.defer-datasource-initialization", "true");
        assertInvalid("defer-datasource-initialization=true is not supported");
    }

    @Test
    void acceptsOnlyLiquibaseFour() {
        assertNull(validator.checkLiquibaseVersion("4.33.0"));
        assertNull(validator.checkLiquibaseVersion("DEV"));
        String error = validator.checkLiquibaseVersion("5.0.2");
        assertNotNull(error);
        assertTrue(error.contains("4.33.0"));
    }

    @Test
    void requiresAdminAliasPerDatabaseForPhaseOne() {
        properties.setPublicChangeLog("classpath:db/changelog/db.changelog-public.xml");
        assertInvalid("no admin alias resolves for database db-host:5432/appdb");
    }

    @Test
    void rejectsDifferentExplicitAliasesInOneDatabase() {
        properties.setPublicChangeLog("classpath:db/changelog/db.changelog-public.xml");
        properties.getAdmin().put("first", admin("jdbc:postgresql://pgbouncer-1/appdb"));
        properties.getAdmin().put("second", admin("jdbc:postgresql://pgbouncer-2/appdb"));
        tenants.get("tenant1").setMigrationAdmin("first");
        tenants.get("tenant2").setMigrationAdmin("second");
        assertInvalid("name different migration-admin aliases");
    }

    @Test
    void rejectsExplicitAdminForTenantWithoutSchema() {
        properties.getAdmin().put("appdb", admin(APP_DB));
        TenantDatabaseProperties tenant = tenant(APP_DB, null);
        tenant.setMigrationAdmin("appdb");
        tenants.put("tenant3", tenant);
        assertInvalid("migration-admin requires a schema");
    }

    @Test
    void rejectsUnknownAlias() {
        tenants.get("tenant1").setMigrationAdmin("missing");
        assertInvalid("migration-admin 'missing' is not defined");
    }

    @Test
    void rejectsCurrentSchemaInTenantUrl() {
        tenants.put("tenant3", tenant(APP_DB + "?currentSchema=other", "tenant3_schema"));
        assertInvalid("must not carry currentSchema");
    }

    @Test
    void rejectsInvalidTenantSchemaAndSharedSchemaAsTenantSchema() {
        tenants.put("tenant3", tenant(APP_DB, "Tenant3"));
        tenants.put("tenant4", tenant(APP_DB, "public"));
        MigrationException exception = assertThrows(MigrationException.class, () -> validator.validate(tenants));
        assertTrue(exception.getMessage().contains("tenants.profile.tenant3"));
        assertTrue(exception.getMessage().contains("tenants.profile.tenant4"));
    }

    @Test
    void rejectsAdminAliasesForTheSameDatabaseAndMissingUrls() {
        properties.getAdmin().put("a", admin(APP_DB));
        properties.getAdmin().put("b", admin("jdbc:postgresql://DB-HOST/appdb?ssl=true"));
        properties.getAdmin().put("c", admin(null));
        MigrationException exception = assertThrows(MigrationException.class, () -> validator.validate(tenants));
        assertTrue(exception.getMessage().contains("point to the same database"));
        assertTrue(exception.getMessage().contains("sqldao.liquibase.admin.c.jdbc-url is required"));
    }

    @Test
    void validatesTenantForRuntimeOnboarding() {
        assertTrue(validator.validateTenant("tenant1", tenants.get("tenant1")).isEmpty());
        assertEquals(1, validator.validateTenant("bad", tenant(APP_DB, "BAD")).size());
    }

    private void assertInvalid(String expectedMessagePart) {
        MigrationException exception = assertThrows(MigrationException.class, () -> validator.validate(tenants));
        assertEquals(MigrationErrorCode.INVALID_CONFIGURATION, exception.getErrorCode());
        assertTrue(exception.getMessage().contains(expectedMessagePart), exception.getMessage());
    }

    private static TenantDatabaseProperties tenant(String jdbcUrl, String schema) {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(jdbcUrl);
        tenant.setSchema(schema);
        return tenant;
    }

    private static AdminDataSourceProperties admin(String jdbcUrl) {
        AdminDataSourceProperties admin = new AdminDataSourceProperties();
        admin.setJdbcUrl(jdbcUrl);
        return admin;
    }
}
