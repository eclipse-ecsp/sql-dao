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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/**
 * Per-schema PostgreSQL advisory lock, held for the whole migration of one schema.
 *
 * <p>The lock is session-level and taken on the migration connection itself, keyed on
 * {@code (namespace, hashtext(schema))}. Advisory locks are scoped to the current database. The connection is
 * evicted from its pool afterwards, which ends the session and releases the lock even if the explicit unlock
 * fails, including when the pod is killed.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class SchemaMigrationLock {

    /** Fixed first key ("SQLD") so that sql-dao locks cannot collide with other advisory-lock users. */
    static final int LOCK_NAMESPACE = 0x53514C44;

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationLock.class);
    private static final long POLL_INTERVAL_MILLIS = 500L;
    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?, hashtext(?))";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?, hashtext(?))";
    private static final String CURRENT_SCHEMA_SQL = "SELECT current_schema()";
    private static final int NAMESPACE_PARAMETER = 1;
    private static final int SCHEMA_PARAMETER = 2;

    /**
     * Work executed on a migration connection while its schema's advisory lock is held.
     */
    @FunctionalInterface
    public interface LockedWork {

        /**
         * Runs the work.
         *
         * @param connection the locked migration connection
         * @throws Exception on failure
         */
        void execute(Connection connection) throws Exception;
    }

    /**
     * Borrows a connection, locks the schema on it, runs the work, then releases the lock and evicts the
     * connection instead of returning it to the pool.
     *
     * @param dataSource the migration datasource
     * @param tenantId the tenant identifier, or {@code null} for shared-schema migrations
     * @param schema the schema to lock; {@code null} locks the connection's {@code current_schema()}
     * @param timeoutSeconds how long to wait for the lock
     * @param work the work to run
     * @throws MigrationException if the lock cannot be acquired or the work fails
     */
    public void withLockedConnection(DataSource dataSource, String tenantId, String schema, int timeoutSeconds,
            LockedWork work) {
        Connection connection = borrow(dataSource, tenantId);
        try {
            String lockSchema = schema != null ? schema : currentSchema(connection);
            acquire(connection, tenantId, lockSchema, timeoutSeconds);
            try {
                work.execute(connection);
            } finally {
                release(connection, lockSchema);
            }
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Migration failed for " + describe(tenantId, schema) + ": "
                    + e.getMessage(), tenantId, MigrationErrorCode.MIGRATION_FAILED, e);
        } finally {
            evict(dataSource, connection);
        }
    }

    /**
     * Acquires the schema's advisory lock, polling {@code pg_try_advisory_lock} because
     * {@code pg_advisory_lock} cannot time out.
     *
     * @param connection the migration connection
     * @param tenantId the tenant identifier, or {@code null}
     * @param schema the schema to lock
     * @param timeoutSeconds how long to wait
     * @throws MigrationException with {@link MigrationErrorCode#LOCK_ACQUISITION_FAILED} on timeout or error
     */
    public void acquire(Connection connection, String tenantId, String schema, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, timeoutSeconds));
        try {
            while (!tryLock(connection, schema)) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis <= 0) {
                    throw new MigrationException("Timed out after " + timeoutSeconds + "s waiting for the migration "
                            + "lock of " + describe(tenantId, schema), tenantId,
                            MigrationErrorCode.LOCK_ACQUISITION_FAILED);
                }
                Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remainingMillis));
            }
            LOGGER.debug("Acquired migration lock for {}", describe(tenantId, schema));
        } catch (SQLException e) {
            throw new MigrationException("Failed to acquire the migration lock of " + describe(tenantId, schema),
                    tenantId, MigrationErrorCode.LOCK_ACQUISITION_FAILED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("Interrupted while waiting for the migration lock of "
                    + describe(tenantId, schema), tenantId, MigrationErrorCode.LOCK_ACQUISITION_FAILED, e);
        }
    }

    /**
     * Releases the schema's advisory lock. Never throws: evicting the connection ends the session anyway.
     *
     * @param connection the migration connection
     * @param schema the locked schema
     */
    public void release(Connection connection, String schema) {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
                statement.setInt(NAMESPACE_PARAMETER, LOCK_NAMESPACE);
                statement.setString(SCHEMA_PARAMETER, schema);
                statement.execute();
            }
        } catch (SQLException e) {
            LOGGER.warn("Could not release the migration lock of schema {}; evicting the connection releases it",
                    schema, e);
        }
    }

    private static boolean tryLock(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setInt(NAMESPACE_PARAMETER, LOCK_NAMESPACE);
            statement.setString(SCHEMA_PARAMETER, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static String currentSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(CURRENT_SCHEMA_SQL)) {
            String schema = resultSet.next() ? resultSet.getString(1) : null;
            return schema != null ? schema : "";
        }
    }

    private static Connection borrow(DataSource dataSource, String tenantId) {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new MigrationException("Failed to obtain a migration connection for " + describe(tenantId, null),
                    tenantId, MigrationErrorCode.MIGRATION_FAILED, e);
        }
    }

    private static void evict(DataSource dataSource, Connection connection) {
        try {
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.evictConnection(connection);
            } else {
                connection.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not evict the migration connection", e);
        }
    }

    static String describe(String tenantId, String schema) {
        String target = tenantId != null ? "tenant " + tenantId : "shared schema";
        return schema != null && !schema.isEmpty() ? target + " (schema " + schema + ")" : target;
    }
}
