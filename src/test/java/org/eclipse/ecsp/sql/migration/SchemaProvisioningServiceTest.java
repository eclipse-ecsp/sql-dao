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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SchemaProvisioningService}.
 */
class SchemaProvisioningServiceTest {

    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private SqlDaoLiquibaseProperties properties;
    private SchemaProvisioningService service;
    private Connection connection;
    private ResultSet existsResult;
    private Statement statement;

    @BeforeEach
    void setUp() throws SQLException {
        properties = new SqlDaoLiquibaseProperties();
        service = new SchemaProvisioningService(properties);
        connection = mock(Connection.class);
        PreparedStatement existsQuery = mock(PreparedStatement.class);
        existsResult = mock(ResultSet.class);
        statement = mock(Statement.class);
        when(connection.prepareStatement(anyString())).thenReturn(existsQuery);
        when(existsQuery.executeQuery()).thenReturn(existsResult);
        when(connection.createStatement()).thenReturn(statement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant1", "_tenant", "orders_eu", "a"})
    void acceptsValidSchemaNames(String schema) {
        assertDoesNotThrow(() -> service.validateSchemaName(schema));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tenant1", "1tenant", "tenant-1", "tenant1\"; DROP SCHEMA x; --", "\"quoted\"",
        "pg_temp", "information_schema", "public", "", " tenant"})
    void rejectsInvalidSchemaNames(String schema) {
        MigrationException exception = assertThrows(MigrationException.class,
                () -> service.validateSchemaName(schema));
        assertEquals(MigrationErrorCode.INVALID_CONFIGURATION, exception.getErrorCode());
    }

    @Test
    void rejectsNullAndOverLengthNames() {
        assertThrows(MigrationException.class, () -> service.validateSchemaName(null));
        assertDoesNotThrow(() -> service.validateSchemaName("a".repeat(MAX_IDENTIFIER_LENGTH)));
        assertThrows(MigrationException.class,
                () -> service.validateSchemaName("a".repeat(MAX_IDENTIFIER_LENGTH + 1)));
    }

    @Test
    void rejectsConfiguredSharedSchemaAsTenantSchema() {
        properties.setPublicSchema("shared");
        assertThrows(MigrationException.class, () -> service.validateSchemaName("shared"));
        assertDoesNotThrow(() -> service.validateSchemaName("public"));
        assertDoesNotThrow(() -> service.validateSharedSchemaName("shared"));
    }

    @Test
    void ensureSchemaSkipsExistingSchema() throws SQLException {
        when(existsResult.next()).thenReturn(true);
        service.ensureSchema(connection, "t1", "tenant1");
        verify(connection, never()).createStatement();
    }

    @Test
    void ensureSchemaCreatesMissingSchemaQuoted() throws SQLException {
        when(existsResult.next()).thenReturn(false);
        service.ensureSchema(connection, "t1", "tenant1");
        verify(statement).execute("CREATE SCHEMA IF NOT EXISTS \"tenant1\"");
    }

    @Test
    void ensureSchemaTreatsDuplicateSchemaAsSuccess() throws SQLException {
        when(existsResult.next()).thenReturn(false);
        doThrow(new SQLException("duplicate", "42P06")).when(statement).execute(anyString());
        assertDoesNotThrow(() -> service.ensureSchema(connection, "t1", "tenant1"));
    }

    @Test
    void ensureSchemaReportsOtherFailures() throws SQLException {
        when(existsResult.next()).thenReturn(false);
        doThrow(new SQLException("permission denied", "42501")).when(statement).execute(anyString());
        MigrationException exception = assertThrows(MigrationException.class,
                () -> service.ensureSchema(connection, "t1", "tenant1"));
        assertEquals(MigrationErrorCode.SCHEMA_CREATION_FAILED, exception.getErrorCode());
        assertEquals("t1", exception.getTenantId());
    }

    @Test
    void ensureSchemaValidatesBeforeTouchingTheDatabase() throws SQLException {
        assertThrows(MigrationException.class, () -> service.ensureSchema(connection, "t1", "Bad-Name"));
        verify(connection, never()).prepareStatement(anyString());
    }
}
