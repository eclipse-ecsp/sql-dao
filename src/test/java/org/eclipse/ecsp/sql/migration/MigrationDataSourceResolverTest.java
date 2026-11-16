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
import org.eclipse.ecsp.sql.authentication.CredentialsProvider;
import org.eclipse.ecsp.sql.dao.utils.SqlDaoUtils;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MigrationDataSourceResolver} and {@link MigrationSession}.
 */
class MigrationDataSourceResolverTest {

    private static final String DB_A = "jdbc:postgresql://db-a-host:5432/orders_db";
    private static final String DB_B = "jdbc:postgresql://db-b-host:5432/billing_db";
    private static final int PARALLELISM = 8;
    private static final int LARGE_POOL = 16;

    private SqlDaoLiquibaseProperties properties;
    private RecordingResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new SqlDaoLiquibaseProperties();
        properties.getAdmin().put("ordersdb", admin("jdbc:postgresql://DB-A-HOST/orders_db?sslmode=require"));
        properties.getAdmin().put("billingdb", admin(DB_B));
        resolver = new RecordingResolver(properties);
    }

    @Test
    void urlMatchIgnoresQueryParametersAndHostCase() {
        assertEquals(Optional.of("ordersdb"), resolver.resolveAdminAlias(tenant(DB_A, "orders_eu")));
    }

    @Test
    void tenantWithoutSchemaSkipsUrlMatch() {
        assertEquals(Optional.empty(), resolver.resolveAdminAlias(tenant(DB_A, null)));
    }

    @Test
    void sessionFallsBackToTenantPool() {
        DataSource tenantPool = mock(DataSource.class);
        TenantDatabaseProperties tenant = tenant("jdbc:postgresql://other/db", "tenant1");
        try (MigrationSession session = resolver.openSession()) {
            assertSame(tenantPool, session.resolve("t1", tenant, tenantPool));
        }
    }

    @Test
    void sessionFailsWhenTenantHasNoPool() {
        TenantDatabaseProperties tenant = tenant("jdbc:postgresql://other/db", "tenant1");
        try (MigrationSession session = resolver.openSession()) {
            MigrationException exception = assertThrows(MigrationException.class,
                    () -> session.resolve("t1", tenant, null));
            assertEquals(MigrationErrorCode.MIGRATION_FAILED, exception.getErrorCode());
        }
    }

    @Test
    void sessionReusesAdminPoolsAndClosesThemOnClose() {
        MigrationSession session = resolver.openSession();
        DataSource first = session.resolve("eu_orders", tenant(DB_A, "orders_eu"), null);
        DataSource second = session.resolve("us_orders", tenant(DB_A, "orders_us"), null);
        assertSame(first, second);
        assertEquals(1, resolver.created.size());
        session.close();
        verify(resolver.created.get("ordersdb")).close();
    }

    @Test
    void destroyClosesSessionsStillOpen() {
        MigrationSession session = resolver.openSession();
        session.resolve("eu_orders", tenant(DB_A, "orders_eu"), null);
        resolver.destroy();
        verify(resolver.created.get("ordersdb")).close();
    }

    @Test
    void distinctDatabasesGroupsTenantsAndResolvesPhaseOneAlias() {
        Map<String, TenantDatabaseProperties> tenants = new HashMap<>();
        tenants.put("us_orders", tenant(DB_A, "orders_us"));
        tenants.put("eu_orders", tenant(DB_A + "?ApplicationName=x", "orders_eu"));
        tenants.put("eu_billing", tenant(DB_B, "billing_eu"));
        List<DatabaseTarget> targets = resolver.distinctDatabases(tenants);
        assertEquals(List.of("db-a-host:5432/orders_db", "db-b-host:5432/billing_db"),
                targets.stream().map(DatabaseTarget::key).toList());
        assertEquals("db-a-host:5432/orders_db", targets.get(0).key());
        assertEquals(List.of("eu_orders", "us_orders"), targets.get(0).tenantIds());
        assertEquals("ordersdb", targets.get(0).adminAlias());
        assertEquals("billingdb", targets.get(1).adminAlias());
    }

    @Test
    void phaseOneNeverFallsBackToTenantPool() {
        DatabaseTarget target = new DatabaseTarget("other:5432/db", "jdbc:postgresql://other/db", List.of("t1"),
                null);
        try (MigrationSession session = resolver.openSession()) {
            MigrationException exception = assertThrows(MigrationException.class,
                    () -> session.resolveForDatabase(target));
            assertEquals(MigrationErrorCode.INVALID_CONFIGURATION, exception.getErrorCode());
        }
    }

    @Test
    void adminPoolIsAtLeastAsLargeAsTheMigrationParallelism() {
        properties.getAdmin().put("appdb", admin("jdbc:postgresql://db-host:5432/appdb"));
        MigrationDataSourceResolver realResolver = new MigrationDataSourceResolver(properties, null);
        assertEquals(AdminDataSourceProperties.DEFAULT_MAX_POOL_SIZE,
                realResolver.adminPoolConfig("appdb").getMaximumPoolSize());

        properties.setParallelism(PARALLELISM);
        assertEquals(PARALLELISM, realResolver.adminPoolConfig("appdb").getMaximumPoolSize());

        properties.getAdmin().get("appdb").setMaxPoolSize(LARGE_POOL);
        assertEquals(LARGE_POOL, realResolver.adminPoolConfig("appdb").getMaximumPoolSize());
    }

    @Test
    void unknownAliasIsConfigurationError() {
        MigrationDataSourceResolver realResolver = new MigrationDataSourceResolver(properties, null);
        MigrationException exception = assertThrows(MigrationException.class,
                () -> realResolver.createAdminPool("missing"));
        assertEquals(MigrationErrorCode.INVALID_CONFIGURATION, exception.getErrorCode());
    }

    @Test
    void adminCredentialsAreReadFromProviderWhenPoolIsCreated() {
        SqlDaoUtils utils = mock(SqlDaoUtils.class);
        CredentialsProvider provider = mock(CredentialsProvider.class);
        when(utils.getClassInstance("com.example.VaultProvider")).thenReturn(provider);
        AdminDataSourceProperties admin = admin("jdbc:postgresql://127.0.0.1:1/unreachable");
        admin.setCredentialProviderBeanName("com.example.VaultProvider");
        properties.getAdmin().put("vault", admin);
        MigrationDataSourceResolver realResolver = new MigrationDataSourceResolver(properties, utils);
        MigrationException exception = assertThrows(MigrationException.class,
                () -> realResolver.createAdminPool("vault"));
        assertEquals(MigrationErrorCode.MIGRATION_FAILED, exception.getErrorCode());
        verify(provider).refreshCredentials();
        verify(provider).getUserName();
        verify(provider).getPassword();
    }

    private static AdminDataSourceProperties admin(String jdbcUrl) {
        AdminDataSourceProperties admin = new AdminDataSourceProperties();
        admin.setJdbcUrl(jdbcUrl);
        admin.setUserName("admin");
        admin.setPassword("secret");
        return admin;
    }

    private static TenantDatabaseProperties tenant(String jdbcUrl, String schema) {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(jdbcUrl);
        tenant.setSchema(schema);
        return tenant;
    }

    /**
     * Resolver that records mock admin pools instead of connecting.
     */
    private static final class RecordingResolver extends MigrationDataSourceResolver {

        private final Map<String, HikariDataSource> created = new HashMap<>();

        RecordingResolver(SqlDaoLiquibaseProperties properties) {
            super(properties, null);
        }

        @Override
        HikariDataSource createAdminPool(String alias) {
            HikariDataSource pool = mock(HikariDataSource.class);
            created.put(alias, pool);
            return pool;
        }
    }
}
