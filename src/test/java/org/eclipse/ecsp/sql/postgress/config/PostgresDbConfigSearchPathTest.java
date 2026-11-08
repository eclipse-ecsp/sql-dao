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

package org.eclipse.ecsp.sql.postgress.config;

import com.zaxxer.hikari.HikariConfig;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tenant pool search path, with and without Liquibase migration support.
 */
class PostgresDbConfigSearchPathTest {

    private PostgresDbConfig config;
    private TenantDatabaseProperties tenant;

    @BeforeEach
    void setUp() {
        config = new PostgresDbConfig();
        tenant = new TenantDatabaseProperties();
        tenant.setJdbcUrl("jdbc:postgresql://db-host:5432/appdb");
        tenant.setUserName("tenant1_app");
        tenant.setPassword("secret");
        tenant.setSchema("tenant1");
    }

    @Test
    void keepsHikariSchemaWhenLiquibaseIsDisabled() {
        HikariConfig hikariConfig = config.buildHikariConfig(tenant);
        assertEquals("tenant1", hikariConfig.getSchema());
        assertNull(hikariConfig.getDataSourceProperties().getProperty("currentSchema"));
    }

    @Test
    void appendsSharedSchemaWhenLiquibaseIsEnabled() {
        ReflectionTestUtils.setField(config, "liquibaseEnabled", true);
        ReflectionTestUtils.setField(config, "publicSchema", "shared");
        HikariConfig hikariConfig = config.buildHikariConfig(tenant);
        assertNull(hikariConfig.getSchema());
        assertEquals("tenant1,shared", hikariConfig.getDataSourceProperties().getProperty("currentSchema"));
    }

    @Test
    void leavesTenantWithoutSchemaUntouched() {
        ReflectionTestUtils.setField(config, "liquibaseEnabled", true);
        tenant.setSchema(null);
        HikariConfig hikariConfig = config.buildHikariConfig(tenant);
        assertNull(hikariConfig.getSchema());
        assertNull(hikariConfig.getDataSourceProperties().getProperty("currentSchema"));
    }
}
