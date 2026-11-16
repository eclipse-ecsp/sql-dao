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

import org.eclipse.ecsp.sql.migration.MigrationException.MigrationErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LiquibaseMigrationService} behavior that needs no database.
 */
class LiquibaseMigrationServiceTest {

    private SqlDaoLiquibaseProperties properties;
    private LiquibaseMigrationService service;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() throws SQLException {
        properties = new SqlDaoLiquibaseProperties();
        properties.setChangeLog("db/changelog/db.changelog-tenant.xml");
        service = new LiquibaseMigrationService(properties, new DefaultResourceLoader());
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
    }

    @Test
    void stripsClasspathPrefixFromChangelogPath() {
        assertEquals("db/changelog/x.xml", LiquibaseMigrationService.changeLogPath("classpath:db/changelog/x.xml"));
        assertEquals("db/changelog/x.xml", LiquibaseMigrationService.changeLogPath(" db/changelog/x.xml "));
    }

    @Test
    void buildsQuotedSearchPath() {
        assertEquals("SET search_path TO \"tenant1\", \"public\"",
                SchemaProvisioningService.setSearchPath(List.of("tenant1", "public")));
    }

    @Test
    void setsTenantThenSharedSearchPathBeforeLiquibaseAndWrapsFailures() throws SQLException {
        MigrationException exception = assertThrows(MigrationException.class,
                () -> service.runMigrations(connection, "t1", "tenant1", properties.getChangeLog()));
        verify(statement).execute("SET search_path TO \"tenant1\", \"public\"");
        assertEquals(MigrationErrorCode.MIGRATION_FAILED, exception.getErrorCode());
        assertEquals("t1", exception.getTenantId());
    }

    @Test
    void phaseOneSearchPathIsOnlyTheSharedSchema() throws SQLException {
        assertThrows(MigrationException.class,
                () -> service.runPublicMigrations(connection, "public", "db/changelog/db.changelog-public.xml"));
        verify(statement).execute("SET search_path TO \"public\"");
    }

    @Test
    void tenantWithoutSchemaKeepsItsSearchPath() throws SQLException {
        assertThrows(MigrationException.class,
                () -> service.runMigrations(connection, "t1", null, properties.getChangeLog()));
        verify(statement, never()).execute(anyString());
    }

    @Test
    void rollbackFailuresUseRollbackErrorCode() {
        MigrationException exception = assertThrows(MigrationException.class,
                () -> service.rollbackMigrations(connection, "t1", "tenant1", 1));
        assertEquals(MigrationErrorCode.ROLLBACK_FAILED, exception.getErrorCode());
    }
}
