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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Migrations in {@code after-ready} mode, with the whole application context: nothing is migrated while the context
 * starts, and the migrations run in the background once the application accepts traffic.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {SqlDaoApplication.class})
@TestPropertySource(locations = "classpath:application-dao-liquibase-test.properties",
        properties = "sqldao.liquibase.startup-mode=after-ready")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LiquibaseAfterReadyIntegrationTest extends LiquibaseSpringTestSupport {

    private static final int TENANT_CHANGESETS = 3;
    private static final long TIMEOUT_SECONDS = 60L;
    private static final long POLL_MILLIS = 50L;

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("jdbcTemplates")
    private Map<String, JdbcTemplate> jdbcTemplates;

    @Test
    void migratesInTheBackgroundOnlyOnceTheApplicationAcceptsTraffic() throws Exception {
        assertFalse(schemaExists("spring_a"), "nothing may be migrated while the context starts");
        assertFalse(schemaExists("spring_b"));

        AvailabilityChangeEvent.publish(context, this, ReadinessState.ACCEPTING_TRAFFIC);

        await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).pollInterval(POLL_MILLIS, TimeUnit.MILLISECONDS)
                .ignoreExceptions().untilAsserted(() -> {
                    assertEquals(TENANT_CHANGESETS, count("spring_a.databasechangelog"));
                    assertEquals(TENANT_CHANGESETS, count("spring_b.databasechangelog"));
                });
        assertEquals(1, jdbcTemplates.get("tenanta").queryForObject("SELECT count(*) FROM customers",
                Integer.class));
        assertEquals("spring_b", jdbcTemplates.get("tenantb").queryForObject("SELECT current_schema()",
                String.class));
    }
}
