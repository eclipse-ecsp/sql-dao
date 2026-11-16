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
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Phases 1 to 3 against a real PostgreSQL: the real advisory lock, schema provisioning, Liquibase and PgJDBC, with
 * several databases on one server. Every test uses its own schemas, so the tests are independent.
 */
@Testcontainers
@SuppressWarnings("resource")
class LiquibaseMigrationFlowIntegrationTest {

    private static final String PUBLIC_CHANGELOG = "db/changelog/db.changelog-flow-public.xml";
    private static final String TENANT_CHANGELOG = "db/changelog/db.changelog-flow-tenant.xml";
    private static final String SIMPLE_CHANGELOG = "db/changelog/db.changelog-flow-simple.xml";
    private static final String FAILING_CHANGELOG = "db/changelog/db.changelog-flow-failing.xml";
    private static final String EXTRA_CHANGELOG = "db/changelog/db.changelog-flow-extra.xml";

    private static final String ORDERS_DB = "orders_db";
    private static final String BILLING_DB = "billing_db";
    private static final String NOINFRA_DB = "noinfra_db";
    private static final String SOLO_DB = "solo_db";

    private static final int PUBLIC_CHANGESETS = 2;
    private static final int TENANT_CHANGESETS = 3;
    private static final int CONCURRENT_TENANTS = 4;
    private static final int PODS = 2;
    private static final int SEED_ROWS_PLUS_ONE = 2;
    private static final long RETRY_DELAY_MS = 100L;
    private static final long TIMEOUT_SECONDS = 120L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName(ORDERS_DB)
            .withUsername("root")
            .withPassword("root");

    @BeforeAll
    static void createDatabases() throws SQLException {
        execute(ORDERS_DB, "CREATE DATABASE " + BILLING_DB);
        execute(ORDERS_DB, "CREATE DATABASE " + NOINFRA_DB);
        execute(ORDERS_DB, "CREATE DATABASE " + SOLO_DB);
    }

    @Test
    void runsPhaseOnePerDatabaseThenMigratesEveryTenantIntoItsOwnSchema() throws SQLException {
        try (Harness harness = new Harness(TENANT_CHANGELOG).withPublicChangeLog()
                .withAdmin("ordersdb", ORDERS_DB).withAdmin("billingdb", BILLING_DB)) {
            Map<String, TenantDatabaseProperties> tenants = new TreeMap<>();
            tenants.put("eu_orders", tenant(ORDERS_DB, "flow_eu"));
            tenants.put("us_orders", tenant(ORDERS_DB, "flow_us"));
            tenants.put("eu_billing", tenant(BILLING_DB, "flow_billing"));

            assertTrue(harness.migrateAll(tenants).isEmpty());

            assertEquals(PUBLIC_CHANGESETS, count(ORDERS_DB, "public.databasechangelog"),
                    "Phase 1 must run once per database, not once per tenant");
            assertEquals(PUBLIC_CHANGESETS, count(BILLING_DB, "public.databasechangelog"));
            assertEquals(TENANT_CHANGESETS, count(ORDERS_DB, "flow_eu.databasechangelog"));
            assertEquals(TENANT_CHANGESETS, count(ORDERS_DB, "flow_us.databasechangelog"));
            assertEquals(TENANT_CHANGESETS, count(BILLING_DB, "flow_billing.databasechangelog"));

            assertNull(scalar(ORDERS_DB, "SELECT to_regclass('public.customers')"), "tables must not leak into public");
            assertNull(scalar(BILLING_DB, "SELECT to_regclass('flow_eu.customers')"),
                    "a tenant of another database must not appear here");

            execute(ORDERS_DB, "INSERT INTO flow_eu.customers (id, name) VALUES (2, 'only-eu')");
            assertEquals(SEED_ROWS_PLUS_ONE, count(ORDERS_DB, "flow_eu.customers"));
            assertEquals(1, count(ORDERS_DB, "flow_us.customers"), "data must stay in its tenant's schema");
        }
    }

    @Test
    void pgTrgmFromPhaseOneIsUsableUnqualifiedFromTenantChangelogsAndTenantPools() throws SQLException {
        try (Harness harness = new Harness(TENANT_CHANGELOG).withPublicChangeLog().withAdmin("ordersdb", ORDERS_DB)) {
            assertTrue(harness.migrateAll(Map.of("trgm", tenant(ORDERS_DB, "flow_trgm"))).isEmpty());

            assertEquals("public", scalar(ORDERS_DB, "SELECT n.nspname FROM pg_extension e "
                    + "JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_trgm'"));
            try (HikariDataSource pool = pool(ORDERS_DB, "flow_trgm,public");
                    Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement()) {
                assertEquals("flow_trgm", single(statement, "SELECT current_schema()"));
                assertEquals("1", single(statement, "SELECT count(*) FROM customers WHERE name % 'seed'"));
                assertEquals("1", single(statement, "SELECT similarity('abc', 'abc')::int"));
            }
        }
    }

    @Test
    void tenantChangelogFailsWithoutPhaseOneAndSucceedsOnceItHasRun() throws SQLException {
        try (Harness harness = new Harness(TENANT_CHANGELOG).withAdmin("noinfradb", NOINFRA_DB)) {
            harness.properties.setFailOnError(false);
            harness.properties.getRetry().setMaxAttempts(1);
            Map<String, TenantDatabaseProperties> tenants = Map.of("late", tenant(NOINFRA_DB, "flow_late"));

            assertEquals(Set.of("late"), harness.migrateTenantsOnly(tenants),
                    "gin_trgm_ops does not exist until Phase 1 has installed pg_trgm");
            assertEquals(1, count(NOINFRA_DB, "flow_late.databasechangelog"), "only the first changeset was applied");

            harness.withPublicChangeLog();
            assertTrue(harness.migrateAll(tenants).isEmpty());
            assertEquals(TENANT_CHANGESETS, count(NOINFRA_DB, "flow_late.databasechangelog"));
        }
    }

    @Test
    void secondRunAppliesNoChangesets() throws SQLException {
        try (Harness harness = new Harness(TENANT_CHANGELOG).withPublicChangeLog().withAdmin("ordersdb", ORDERS_DB)) {
            Map<String, TenantDatabaseProperties> tenants = Map.of("again", tenant(ORDERS_DB, "flow_again"));
            assertTrue(harness.migrateAll(tenants).isEmpty());
            String before = changelogFingerprint(ORDERS_DB, "flow_again");
            String publicBefore = changelogFingerprint(ORDERS_DB, "public");

            assertTrue(harness.migrateAll(tenants).isEmpty());

            assertEquals(before, changelogFingerprint(ORDERS_DB, "flow_again"));
            assertEquals(publicBefore, changelogFingerprint(ORDERS_DB, "public"));
            assertEquals(1, count(ORDERS_DB, "flow_again.customers"), "the seed row must not be inserted twice");
        }
    }

    @Test
    void evictsTheMigrationConnectionAfterSuccessSoNothingLeaksIntoThePool() throws SQLException {
        try (Harness harness = new Harness(SIMPLE_CHANGELOG)) {
            HikariDataSource pool = harness.tenantPool("evict", ORDERS_DB);
            ConnectionState before = connectionState(pool);

            assertTrue(harness.migrateTenantsOnly(Map.of("evict", tenant(ORDERS_DB, "flow_evict"))).isEmpty());

            ConnectionState after = connectionState(pool);
            assertNotEquals(before.backendPid(), after.backendPid(), "the migration connection must be evicted");
            assertEquals(before.searchPath(), after.searchPath(), "the migration search path must not leak");
            assertEquals(0, after.advisoryLocks(), "the advisory lock must be released");
            assertEquals(1, count(ORDERS_DB, "flow_evict.databasechangelog"));
        }
    }

    @Test
    void evictsTheMigrationConnectionAfterFailureToo() throws SQLException {
        try (Harness harness = new Harness(FAILING_CHANGELOG)) {
            harness.properties.setFailOnError(false);
            harness.properties.getRetry().setMaxAttempts(1);
            HikariDataSource pool = harness.tenantPool("broken", ORDERS_DB);
            ConnectionState before = connectionState(pool);

            Map<String, TenantDatabaseProperties> tenants = Map.of("broken", tenant(ORDERS_DB, "flow_broken"));

            assertEquals(Set.of("broken"), harness.migrateTenantsOnly(tenants));

            ConnectionState after = connectionState(pool);
            assertNotEquals(before.backendPid(), after.backendPid(), "the failed connection must be evicted");
            assertEquals(before.searchPath(), after.searchPath());
            assertEquals(0, after.advisoryLocks(), "the advisory lock must not outlive a failed migration");
            assertEquals(1, count(ORDERS_DB, "flow_broken.databasechangelog"), "the first changeset stays applied");
        }
    }

    @Test
    void twoPodsMigratingTheSameFreshSchemasApplyEveryChangesetExactlyOnce() throws Exception {
        try (Harness setup = new Harness(TENANT_CHANGELOG).withPublicChangeLog().withAdmin("ordersdb", ORDERS_DB)) {
            Map<String, TenantDatabaseProperties> tenants = new TreeMap<>();
            for (int i = 1; i <= CONCURRENT_TENANTS; i++) {
                tenants.put("pod_tenant" + i, tenant(ORDERS_DB, "flow_pod" + i));
            }
            try (MigrationSession session = setup.resolver.openSession()) {
                setup.infrastructureRunner.runInfrastructureMigrations(tenants, session);
            }

            CyclicBarrier together = new CyclicBarrier(PODS);
            List<Future<Set<String>>> pods = new ArrayList<>();
            List<Harness> harnesses = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(PODS)) {
                for (int pod = 0; pod < PODS; pod++) {
                    Harness harness = new Harness(TENANT_CHANGELOG).withAdmin("ordersdb", ORDERS_DB);
                    harness.properties.setParallelism(PODS);
                    // A retry would hide a race: the advisory lock alone must keep the pods apart.
                    harness.properties.getRetry().setMaxAttempts(1);
                    harnesses.add(harness);
                    pods.add(executor.submit(() -> {
                        together.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        return harness.migrateTenantsOnly(tenants);
                    }));
                }
                for (Future<Set<String>> pod : pods) {
                    assertTrue(get(pod).isEmpty());
                }
            } finally {
                harnesses.forEach(Harness::close);
            }

            for (int i = 1; i <= CONCURRENT_TENANTS; i++) {
                String schema = "flow_pod" + i;
                assertEquals(TENANT_CHANGESETS, count(ORDERS_DB, schema + ".databasechangelog"), schema);
                assertEquals(1, count(ORDERS_DB, schema + ".customers"), schema + " must be seeded exactly once");
                assertFalse(isChangelogLocked(ORDERS_DB, schema), schema);
            }
        }
    }

    @Test
    void releasesStaleChangelogLockWhenEnabled() throws SQLException {
        lockedSchemaWithPendingChangeset("flow_stale_on");
        try (Harness harness = new Harness(EXTRA_CHANGELOG).withAdmin("ordersdb", ORDERS_DB)) {
            harness.properties.setReleaseStaleLocks(true);

            assertTrue(harness.migrateTenantsOnly(Map.of("stale", tenant(ORDERS_DB, "flow_stale_on"))).isEmpty());
        }
        assertNotNull(scalar(ORDERS_DB, "SELECT to_regclass('flow_stale_on.extras')"), "the changeset was applied");
        assertFalse(isChangelogLocked(ORDERS_DB, "flow_stale_on"));
    }

    @Test
    void leavesTheChangelogLockAloneAndFailsFastWhenStaleLockReleaseIsDisabled() throws SQLException {
        lockedSchemaWithPendingChangeset("flow_stale_off");
        try (Harness harness = new Harness(EXTRA_CHANGELOG).withAdmin("ordersdb", ORDERS_DB)) {
            harness.properties.setReleaseStaleLocks(false);
            harness.properties.setChangelogLockWaitMinutes(0);
            harness.properties.setFailOnError(false);
            harness.properties.getRetry().setMaxAttempts(1);

            Map<String, TenantDatabaseProperties> tenants = Map.of("stale", tenant(ORDERS_DB, "flow_stale_off"));

            assertEquals(Set.of("stale"), harness.migrateTenantsOnly(tenants));
        }
        assertNull(scalar(ORDERS_DB, "SELECT to_regclass('flow_stale_off.extras')"), "nothing may be applied");
        assertTrue(isChangelogLocked(ORDERS_DB, "flow_stale_off"), "a lock that is not ours must be left alone");
    }

    @Test
    void rollbackRevertsTheLastChangesetAndTheNextRunAppliesItAgain() throws SQLException {
        Map<String, TenantDatabaseProperties> tenants = Map.of("rb", tenant(ORDERS_DB, "flow_rb"));
        try (Harness harness = new Harness(TENANT_CHANGELOG).withPublicChangeLog().withAdmin("ordersdb", ORDERS_DB)) {
            assertTrue(harness.migrateAll(tenants).isEmpty());
            assertEquals(1, count(ORDERS_DB, "flow_rb.customers"));

            harness.tenantRunner.rollbackTenant("rb", tenants.get("rb"), null, 1);

            assertEquals(TENANT_CHANGESETS - 1, count(ORDERS_DB, "flow_rb.databasechangelog"));
            assertEquals(0, count(ORDERS_DB, "flow_rb.customers"), "the seed changeset must be rolled back");
            assertNotNull(scalar(ORDERS_DB, "SELECT to_regclass('flow_rb.customers_name_trgm')"),
                    "only the last changeset is rolled back");

            assertTrue(harness.migrateTenantsOnly(tenants).isEmpty());
            assertEquals(TENANT_CHANGESETS, count(ORDERS_DB, "flow_rb.databasechangelog"));
            assertEquals(1, count(ORDERS_DB, "flow_rb.customers"));
        }
    }

    @Test
    void invalidSchemaNameFailsOnlyItsTenantAndNeverReachesSql() throws SQLException {
        String evil = "evil\"; DROP SCHEMA flow_good CASCADE; --";
        try (Harness harness = new Harness(TENANT_CHANGELOG).withPublicChangeLog().withAdmin("ordersdb", ORDERS_DB)) {
            harness.properties.setFailOnError(false);
            harness.properties.getRetry().setMaxAttempts(1);
            Map<String, TenantDatabaseProperties> tenants = new TreeMap<>();
            tenants.put("a_good", tenant(ORDERS_DB, "flow_good"));
            tenants.put("b_evil", tenant(ORDERS_DB, evil));
            assertEquals("0", scalar(ORDERS_DB, "SELECT count(*) FROM pg_namespace WHERE nspname = 'flow_good'"));

            assertEquals(Set.of("b_evil"), harness.migrateAll(tenants));

            assertEquals(TENANT_CHANGESETS, count(ORDERS_DB, "flow_good.databasechangelog"),
                    "the schema is created on demand and the other tenant is migrated");
            assertEquals("0", scalar(ORDERS_DB, "SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'evil%'"));
        }
    }

    @Test
    void tenantWithoutSchemaIsMigratedInTheDefaultSchemaOfItsOwnDatabase() throws SQLException {
        try (Harness harness = new Harness(SIMPLE_CHANGELOG)) {
            harness.tenantPool("solo", SOLO_DB);

            assertTrue(harness.migrateTenantsOnly(Map.of("solo", tenant(SOLO_DB, null))).isEmpty());

            assertNotNull(scalar(SOLO_DB, "SELECT to_regclass('public.customers')"));
            assertEquals(1, count(SOLO_DB, "public.databasechangelog"));
            assertFalse(isChangelogLocked(SOLO_DB, "public"));
        }
    }

    @Test
    void failOnErrorStopsStartupWithTheFailingTenant() {
        Map<String, TenantDatabaseProperties> tenants = Map.of("fatal", tenant(ORDERS_DB, "flow_fatal"));
        try (Harness harness = new Harness(FAILING_CHANGELOG).withAdmin("ordersdb", ORDERS_DB)) {
            harness.properties.getRetry().setMaxAttempts(1);
            MigrationException exception = assertThrows(MigrationException.class,
                    () -> harness.migrateTenantsOnly(tenants));
            assertEquals("fatal", exception.getTenantId());
            assertEquals(MigrationErrorCode.MIGRATION_FAILED, exception.getErrorCode());
        }
    }

    private static TenantDatabaseProperties tenant(String database, String schema) {
        TenantDatabaseProperties tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl(url(database));
        tenant.setSchema(schema);
        return tenant;
    }

    private static String url(String database) {
        return postgres.getJdbcUrl().replace("/" + ORDERS_DB, "/" + database);
    }

    private static HikariDataSource pool(String database, String currentSchema) {
        HikariConfig config = new HikariConfig();
        String url = url(database);
        config.setJdbcUrl(currentSchema == null ? url
                : url + (url.contains("?") ? "&" : "?") + "currentSchema=" + currentSchema);
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), postgres.getUsername(), postgres.getPassword());
    }

    private static void execute(String database, String sql) throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(String database, String sql) throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            return single(statement, sql);
        }
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static int count(String database, String table) throws SQLException {
        return Integer.parseInt(scalar(database, "SELECT count(*) FROM " + table));
    }

    private static String changelogFingerprint(String database, String schema) throws SQLException {
        return scalar(database, "SELECT count(*) || '/' || max(orderexecuted) || '/' || max(dateexecuted)::text FROM "
                + schema + ".databasechangelog");
    }

    private static boolean isChangelogLocked(String database, String schema) throws SQLException {
        return Boolean.parseBoolean(scalar(database, "SELECT locked::text FROM " + schema + ".databasechangeloglock"));
    }

    /**
     * Migrates the schema with another changelog, then leaves its changelog lock held by a pod that no longer exists.
     */
    private static void lockedSchemaWithPendingChangeset(String schema) throws SQLException {
        try (Harness harness = new Harness(SIMPLE_CHANGELOG).withAdmin("ordersdb", ORDERS_DB)) {
            assertTrue(harness.migrateTenantsOnly(Map.of("stale", tenant(ORDERS_DB, schema))).isEmpty());
        }
        execute(ORDERS_DB, "UPDATE " + schema + ".databasechangeloglock "
                + "SET locked = true, lockgranted = now(), lockedby = 'dead-pod (10.0.0.1)'");
    }

    private static ConnectionState connectionState(DataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            return new ConnectionState(Integer.parseInt(single(statement, "SELECT pg_backend_pid()")),
                    single(statement, "SHOW search_path"),
                    Integer.parseInt(single(statement, "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                            + "AND classid = " + SchemaMigrationLock.LOCK_NAMESPACE)));
        }
    }

    private static <T> T get(Future<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private record ConnectionState(int backendPid, String searchPath, int advisoryLocks) {
    }

    /**
     * One application instance: its own properties, resolver, runners and pools.
     */
    private static final class Harness implements AutoCloseable {

        private final SqlDaoLiquibaseProperties properties = new SqlDaoLiquibaseProperties();
        private final MigrationDataSourceResolver resolver = new MigrationDataSourceResolver(properties, null);
        private final Map<String, DataSource> targetDataSources = new ConcurrentHashMap<>();
        private final List<HikariDataSource> pools = new ArrayList<>();
        private final InfrastructureMigrationRunner infrastructureRunner;
        private final TenantMigrationRunner tenantRunner;

        Harness(String changeLog) {
            properties.setChangeLog(changeLog);
            properties.getRetry().setDelayMs(RETRY_DELAY_MS);
            SchemaMigrationLock lock = new SchemaMigrationLock();
            LiquibaseMigrationService liquibase = new LiquibaseMigrationService(properties,
                    new DefaultResourceLoader());
            MigrationRetryExecutor retry = new MigrationRetryExecutor(properties);
            infrastructureRunner = new InfrastructureMigrationRunner(properties, resolver, lock, liquibase, retry);
            tenantRunner = new TenantMigrationRunner(properties, resolver, lock,
                    new SchemaProvisioningService(properties), liquibase, infrastructureRunner,
                    mock(MigrationConfigurationValidator.class), retry, targetDataSources);
        }

        Harness withPublicChangeLog() {
            properties.setPublicChangeLog(PUBLIC_CHANGELOG);
            return this;
        }

        Harness withAdmin(String alias, String database) {
            AdminDataSourceProperties admin = new AdminDataSourceProperties();
            admin.setJdbcUrl(url(database));
            admin.setUserName(postgres.getUsername());
            admin.setPassword(postgres.getPassword());
            properties.getAdmin().put(alias, admin);
            return this;
        }

        HikariDataSource tenantPool(String tenantId, String database) {
            HikariDataSource pool = pool(database, null);
            pools.add(pool);
            targetDataSources.put(tenantId, pool);
            return pool;
        }

        Set<String> migrateAll(Map<String, TenantDatabaseProperties> tenants) {
            try (MigrationSession session = resolver.openSession()) {
                Set<String> failedDatabases = infrastructureRunner.runInfrastructureMigrations(tenants, session);
                return tenantRunner.migrateAllTenants(tenants, failedDatabases, session);
            }
        }

        Set<String> migrateTenantsOnly(Map<String, TenantDatabaseProperties> tenants) {
            try (MigrationSession session = resolver.openSession()) {
                return tenantRunner.migrateAllTenants(tenants, Set.of(), session);
            }
        }

        @Override
        public void close() {
            pools.forEach(HikariDataSource::close);
        }
    }
}
