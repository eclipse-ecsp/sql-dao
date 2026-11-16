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

import org.eclipse.ecsp.sql.SqlDaoApplication;
import org.eclipse.ecsp.sql.multitenancy.TenantAwareDataSource;
import org.eclipse.ecsp.sql.multitenancy.TenantContext;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole application context with Liquibase enabled in {@code blocking} mode: startup ordering, the search path of
 * the tenant pools, the routing datasource, and runtime onboarding and removal of tenants.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {SqlDaoApplication.class})
@TestPropertySource(locations = "classpath:application-dao-liquibase-test.properties")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LiquibaseBlockingStartupIntegrationTest extends LiquibaseSpringTestSupport {

    private static final int TENANT_CHANGESETS = 3;
    private static final int PUBLIC_CHANGESETS = 2;
    private static final int ROWS_AFTER_INSERT = 2;
    private static final long TIMEOUT_SECONDS = 60L;
    private static final long POLL_MILLIS = 10L;

    @Autowired
    private MigrationStartupCoordinator coordinator;

    @Autowired
    @Qualifier("jdbcTemplates")
    private Map<String, JdbcTemplate> jdbcTemplates;

    @Autowired
    private TenantAwareDataSource tenantAwareDataSource;

    @Autowired
    @Qualifier("targetDataSources")
    private Map<String, DataSource> targetDataSources;

    @Autowired
    @Qualifier("tenantConfigMap")
    private Map<String, TenantDatabaseProperties> tenantConfigMap;

    @Autowired
    @Qualifier("dataSource")
    private DataSource routingDataSource;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void startupMigratesTenantSchemasBeforeJdbcTemplatesAreUsed() {
        JdbcTemplate tenantA = jdbcTemplates.get("tenanta");

        assertEquals("spring_a", tenantA.queryForObject("SELECT current_schema()", String.class));
        assertEquals(1, tenantA.queryForObject("SELECT count(*) FROM customers", Integer.class));
        assertEquals(1, tenantA.queryForObject("SELECT count(*) FROM customers WHERE name % 'seed'", Integer.class),
                "pg_trgm from Phase 1 must resolve unqualified through the tenant pool's search path");
    }

    @Test
    void eachTenantHasItsOwnChangelogAndPhaseOneRanOnce() throws Exception {
        assertEquals(TENANT_CHANGESETS, count("spring_a.databasechangelog"));
        assertEquals(TENANT_CHANGESETS, count("spring_b.databasechangelog"));
        assertEquals(PUBLIC_CHANGESETS, count("public.databasechangelog"));
        assertEquals("false", scalar("SELECT locked::text FROM spring_a.databasechangeloglock"));
    }

    @Test
    void tenantDataStaysInItsOwnSchema() throws Exception {
        JdbcTemplate tenantB = jdbcTemplates.get("tenantb");
        tenantB.update("INSERT INTO customers (id, name) VALUES (2, 'only-b')");
        try {
            assertEquals(ROWS_AFTER_INSERT, count("spring_b.customers"));
            assertEquals(1, count("spring_a.customers"));
            assertEquals(1, jdbcTemplates.get("tenanta").queryForObject("SELECT count(*) FROM customers",
                    Integer.class));
        } finally {
            tenantB.update("DELETE FROM customers WHERE id = 2");
        }
    }

    @Test
    void routingDataSourceFollowsTheTenantContextIntoItsSchema() throws Exception {
        TenantContext.setCurrentTenant("tenantb");

        assertEquals("spring_b", new JdbcTemplate(routingDataSource).queryForObject("SELECT current_schema()",
                String.class));
    }

    @Test
    void startingAgainAppliesNoChangesets() throws Exception {
        String before = changelogFingerprint("spring_a") + changelogFingerprint("spring_b")
                + changelogFingerprint("public");

        coordinator.afterPropertiesSet();

        assertEquals(before, changelogFingerprint("spring_a") + changelogFingerprint("spring_b")
                + changelogFingerprint("public"));
    }

    @Test
    void onboardingMigratesNewSchemaBeforeRouteIsPublishedAndRemovalKeepsSchema() throws Exception {
        TenantDatabaseProperties tenant = tenantProperties(postgres.getJdbcUrl(), "spring_c", "springCPool");

        assertTrue(tenantAwareDataSource.addOrUpdateTenantDataSource("tenantc", tenant));

        assertTrue(targetDataSources.containsKey("tenantc"));
        assertEquals(TENANT_CHANGESETS, count("spring_c.databasechangelog"));
        TenantContext.setCurrentTenant("tenantc");
        JdbcTemplate routed = new JdbcTemplate(routingDataSource);
        assertEquals("spring_c", routed.queryForObject("SELECT current_schema()", String.class));
        assertEquals(1, routed.queryForObject("SELECT count(*) FROM customers", Integer.class));
        TenantContext.clear();

        String before = changelogFingerprint("spring_c");
        assertTrue(tenantAwareDataSource.addOrUpdateTenantDataSource("tenantc", tenant), "updating an existing tenant");
        assertEquals(before, changelogFingerprint("spring_c"), "an update applies no changesets again");

        assertTrue(tenantAwareDataSource.removeTenantDataSource("tenantc"));
        assertFalse(targetDataSources.containsKey("tenantc"));
        assertFalse(tenantConfigMap.containsKey("tenantc"));
        assertEquals(TENANT_CHANGESETS, count("spring_c.databasechangelog"), "removal never drops the schema");
    }

    @Test
    void onboardingRejectsTenantUrlThatCarriesCurrentSchema() throws Exception {
        TenantDatabaseProperties tenant = tenantProperties(postgres.getJdbcUrl() + "&currentSchema=other",
                "spring_d", "springDPool");

        assertFalse(tenantAwareDataSource.addOrUpdateTenantDataSource("tenantd", tenant));

        assertFalse(targetDataSources.containsKey("tenantd"));
        assertFalse(schemaExists("spring_d"), "nothing may be created for a rejected tenant");
    }

    @Test
    void onboardingWithInvalidSchemaFailsWithoutLeavingRouteOrPool() throws Exception {
        TenantDatabaseProperties tenant = tenantProperties(postgres.getJdbcUrl(), "Bad-Name", "springEPool");

        assertFalse(tenantAwareDataSource.addOrUpdateTenantDataSource("tenante", tenant));

        assertFalse(targetDataSources.containsKey("tenante"));
        assertFalse(tenantConfigMap.containsKey("tenante"));
        assertFalse(schemaExists("Bad-Name"));
    }

    @Test
    void removalWaitsForOnboardingInProgressAndLeavesTheTenantRemoved() throws Exception {
        TenantDatabaseProperties tenant = tenantProperties(postgres.getJdbcUrl(), "spring_racer", "springRacerPool");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> onboarding = executor.submit(
                    () -> tenantAwareDataSource.addOrUpdateTenantDataSource("racer", tenant));
            // Phase 2 has created the schema, so the migration is under way and holds the tenant's lock.
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).pollInterval(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    .ignoreExceptions().until(() -> schemaExists("spring_racer"));

            boolean removed = tenantAwareDataSource.removeTenantDataSource("racer");

            assertTrue(removed);
            assertTrue(onboarding.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse(targetDataSources.containsKey("racer"),
                    "a removal that started during onboarding must not be undone by it");
            assertFalse(tenantConfigMap.containsKey("racer"));
            assertEquals(TENANT_CHANGESETS, count("spring_racer.databasechangelog"));
        } finally {
            executor.shutdownNow();
        }
    }
}
