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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SchemaMigrationLock}.
 */
class SchemaMigrationLockTest {

    private static final int SCHEMA_PARAMETER = 2;
    private static final int LOCK_AND_UNLOCK = 2;

    private final SchemaMigrationLock lock = new SchemaMigrationLock();
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet lockResult;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        lockResult = mock(ResultSet.class);
        dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
    }

    @Test
    void acquiresLockKeyedOnNamespaceAndSchema() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        lock.acquire(connection, "t1", "tenant1", 1);
        verify(statement).setInt(1, SchemaMigrationLock.LOCK_NAMESPACE);
        verify(statement).setString(SCHEMA_PARAMETER, "tenant1");
    }

    @Test
    void failsWhenLockIsNotAcquiredInTime() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(false);
        MigrationException exception = assertThrows(MigrationException.class,
                () -> lock.acquire(connection, "t1", "tenant1", 0));
        assertEquals(MigrationErrorCode.LOCK_ACQUISITION_FAILED, exception.getErrorCode());
        assertEquals("t1", exception.getTenantId());
    }

    @Test
    void reportsLockQueryFailures() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("broken"));
        MigrationException exception = assertThrows(MigrationException.class,
                () -> lock.acquire(connection, "t1", "tenant1", 1));
        assertEquals(MigrationErrorCode.LOCK_ACQUISITION_FAILED, exception.getErrorCode());
    }

    @Test
    void releaseRollsBackOpenTransactionAndNeverThrows() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        when(statement.execute()).thenThrow(new SQLException("aborted"));
        assertDoesNotThrow(() -> lock.release(connection, "tenant1"));
        verify(connection).rollback();
    }

    @Test
    void runsWorkUnderLockAndEvictsConnection() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean();
        lock.withLockedConnection(dataSource, "t1", "tenant1", 1, c -> {
            assertSame(connection, c);
            ran.set(true);
        });
        assertTrue(ran.get());
        verify(statement).execute();
        verify(dataSource).evictConnection(connection);
    }

    @Test
    void wrapsWorkFailureAndStillEvicts() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        MigrationException exception = assertThrows(MigrationException.class,
                () -> lock.withLockedConnection(dataSource, "t1", "tenant1", 1, c -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals(MigrationErrorCode.MIGRATION_FAILED, exception.getErrorCode());
        verify(statement).execute();
        verify(dataSource).evictConnection(connection);
    }

    @Test
    void rethrowsMigrationExceptionsUnchanged() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        MigrationException original = new MigrationException("x", "t1", MigrationErrorCode.SCHEMA_CREATION_FAILED);
        MigrationException exception = assertThrows(MigrationException.class,
                () -> lock.withLockedConnection(dataSource, "t1", "tenant1", 1, c -> {
                    throw original;
                }));
        assertSame(original, exception);
    }

    @Test
    void locksCurrentSchemaForTenantWithoutSchema() throws SQLException {
        Statement currentSchemaQuery = mock(Statement.class);
        ResultSet currentSchema = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(currentSchemaQuery);
        when(currentSchemaQuery.executeQuery("SELECT current_schema()")).thenReturn(currentSchema);
        when(currentSchema.next()).thenReturn(true);
        when(currentSchema.getString(1)).thenReturn("tenant_owner");
        when(lockResult.getBoolean(1)).thenReturn(true);
        lock.withLockedConnection(dataSource, "t1", null, 1, c -> {
        });
        verify(statement, times(LOCK_AND_UNLOCK)).setString(SCHEMA_PARAMETER, "tenant_owner");
    }

    @Test
    void doesNotRunWorkWhenConnectionCannotBeObtained() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));
        MigrationException exception = assertThrows(MigrationException.class,
                () -> lock.withLockedConnection(dataSource, "t1", "tenant1", 1, c -> {
                    throw new AssertionError("must not run");
                }));
        assertEquals(MigrationErrorCode.MIGRATION_FAILED, exception.getErrorCode());
    }
}
