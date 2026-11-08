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

import liquibase.GlobalConfiguration;
import liquibase.Scope;
import liquibase.analytics.configuration.AnalyticsArgs;
import liquibase.command.CommandScope;
import liquibase.command.core.RollbackCountCommandStep;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DatabaseChangelogCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs Liquibase commands on a migration connection through the command API.
 *
 * <p>For every run, the connection's search path is set before the Liquibase {@code Database} is built, so that
 * unqualified raw SQL resolves to the target schema; {@code liquibaseSchemaName} places
 * {@code DATABASECHANGELOG} and {@code DATABASECHANGELOGLOCK} inside that schema. The caller holds the schema's
 * advisory lock and evicts the connection afterwards.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class LiquibaseMigrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiquibaseMigrationService.class);
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final SqlDaoLiquibaseProperties properties;
    private final ClassLoader classLoader;

    /**
     * Creates the service.
     *
     * @param properties the Liquibase migration properties
     * @param resourceLoader the application resource loader, whose class loader resolves changelogs
     */
    public LiquibaseMigrationService(SqlDaoLiquibaseProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        ClassLoader loader = resourceLoader.getClassLoader();
        this.classLoader = loader != null ? loader : LiquibaseMigrationService.class.getClassLoader();
    }

    /**
     * Applies pending tenant changesets (Phase 3). The search path is the tenant schema, then the shared schema.
     *
     * @param connection the locked migration connection
     * @param tenantId the tenant identifier
     * @param schema the tenant schema, or {@code null} to use the connection's default schema
     * @param changeLog the tenant changelog
     * @throws MigrationException with {@link MigrationErrorCode#MIGRATION_FAILED} on failure
     */
    public void runMigrations(Connection connection, String tenantId, String schema, String changeLog) {
        List<String> searchPath = schema == null ? List.of() : List.of(schema, this.properties.getPublicSchema());
        execute(connection, tenantId, schema, searchPath, MigrationErrorCode.MIGRATION_FAILED,
                database -> update(database, changeLog));
    }

    /**
     * Applies pending shared-schema changesets (Phase 1).
     *
     * @param connection the locked migration connection
     * @param schema the shared schema
     * @param changeLog the public changelog
     * @throws MigrationException with {@link MigrationErrorCode#MIGRATION_FAILED} on failure
     */
    public void runPublicMigrations(Connection connection, String schema, String changeLog) {
        execute(connection, null, schema, List.of(schema), MigrationErrorCode.MIGRATION_FAILED,
                database -> update(database, changeLog));
    }

    /**
     * Rolls back the last {@code count} tenant changesets with Liquibase {@code rollbackCount}.
     *
     * @param connection the locked migration connection
     * @param tenantId the tenant identifier
     * @param schema the tenant schema, or {@code null}
     * @param count number of changesets to roll back
     * @throws MigrationException with {@link MigrationErrorCode#ROLLBACK_FAILED} on failure
     */
    public void rollbackMigrations(Connection connection, String tenantId, String schema, int count) {
        List<String> searchPath = schema == null ? List.of() : List.of(schema, this.properties.getPublicSchema());
        execute(connection, tenantId, schema, searchPath, MigrationErrorCode.ROLLBACK_FAILED,
                database -> rollbackCount(database, this.properties.getChangeLog(), count));
    }

    private void execute(Connection connection, String tenantId, String schema, List<String> searchPath,
            MigrationErrorCode errorCode, LiquibaseCommand command) {
        String target = SchemaMigrationLock.describe(tenantId, schema);
        try (ClassLoaderResourceAccessor resourceAccessor = new ClassLoaderResourceAccessor(this.classLoader)) {
            if (!searchPath.isEmpty()) {
                applySearchPath(connection, searchPath);
            }
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            if (schema != null) {
                database.setDefaultSchemaName(schema);
                database.setLiquibaseSchemaName(schema);
            }
            Scope.child(scopeValues(database, resourceAccessor), () -> {
                if (this.properties.isReleaseStaleLocks()) {
                    releaseStaleLock(database, target);
                }
                command.run(database);
            });
            LOGGER.info("Liquibase run completed for {}", target);
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Liquibase run failed for " + target + ": " + e.getMessage(), tenantId,
                    errorCode, e);
        }
    }

    private Map<String, Object> scopeValues(Database database, ClassLoaderResourceAccessor resourceAccessor) {
        Map<String, Object> values = new HashMap<>();
        values.put(Scope.Attr.database.name(), database);
        values.put(Scope.Attr.resourceAccessor.name(), resourceAccessor);
        values.put(Scope.Attr.ui.name(), new LoggerUIService());
        values.put(GlobalConfiguration.CHANGELOGLOCK_WAIT_TIME.getKey(),
                (long) this.properties.getChangelogLockWaitMinutes());
        values.put(AnalyticsArgs.ENABLED.getKey(), Boolean.FALSE);
        return values;
    }

    private static void applySearchPath(Connection connection, List<String> schemas) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(SqlIdentifiers.setSearchPath(schemas));
        }
    }

    /**
     * The caller holds the schema's advisory lock, so no live sql-dao migrator owns a LOCKED row.
     */
    private static void releaseStaleLock(Database database, String target) throws Exception {
        LockService lockService = LockServiceFactory.getInstance().getLockService(database);
        DatabaseChangeLogLock[] locks = lockService.listLocks();
        if (locks.length == 0) {
            return;
        }
        LOGGER.warn("Releasing stale DATABASECHANGELOGLOCK for {}, held by {} since {}", target,
                locks[0].getLockedBy(), locks[0].getLockGranted());
        lockService.forceReleaseLock();
    }

    private static void update(Database database, String changeLog) throws Exception {
        CommandScope update = new CommandScope(UpdateCommandStep.COMMAND_NAME);
        update.addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, changeLogPath(changeLog));
        update.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database);
        update.execute();
    }

    private static void rollbackCount(Database database, String changeLog, int count) throws Exception {
        CommandScope rollback = new CommandScope(RollbackCountCommandStep.COMMAND_NAME);
        rollback.addArgumentValue(DatabaseChangelogCommandStep.CHANGELOG_FILE_ARG, changeLogPath(changeLog));
        rollback.addArgumentValue(RollbackCountCommandStep.COUNT_ARG, count);
        rollback.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database);
        rollback.execute();
    }

    /**
     * Liquibase strips {@code classpath:} itself; stripping it here keeps the changeset identity deterministic.
     *
     * @param changeLog the configured changelog path
     * @return the path resolved by the class loader
     */
    static String changeLogPath(String changeLog) {
        return StringUtils.removeStart(changeLog.trim(), CLASSPATH_PREFIX);
    }

    /**
     * A Liquibase command run inside the migration scope.
     */
    @FunctionalInterface
    private interface LiquibaseCommand {
        void run(Database database) throws Exception;
    }
}
