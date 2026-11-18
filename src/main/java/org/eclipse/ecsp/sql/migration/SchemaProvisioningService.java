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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates schema names and creates missing tenant schemas (Phase 2).
 *
 * <p>Schema names are interpolated into DDL and {@code SET search_path}, because PostgreSQL accepts no bind
 * parameters for identifiers. Every name is therefore validated before it reaches a statement, and quoted.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class SchemaProvisioningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaProvisioningService.class);

    private static final Pattern SCHEMA_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final String RESERVED_PREFIX = "pg_";
    private static final String INFORMATION_SCHEMA = "information_schema";
    private static final Set<String> DUPLICATE_SQL_STATES = Set.of("42P06", "23505");
    private static final String SCHEMA_EXISTS_SQL = "SELECT 1 FROM pg_namespace WHERE nspname = ?";

    private final SqlDaoLiquibaseProperties properties;

    /**
     * Creates the service.
     *
     * @param properties the Liquibase migration properties
     */
    public SchemaProvisioningService(SqlDaoLiquibaseProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates a tenant schema name. In addition to the shared-schema rules, a tenant schema must not equal the
     * configured {@code public-schema}.
     *
     * @param schema the tenant schema name
     * @throws MigrationException with {@link MigrationErrorCode#INVALID_CONFIGURATION} if the name is rejected
     */
    public void validateSchemaName(String schema) {
        validateSharedSchemaName(schema);
        if (schema.equals(this.properties.getPublicSchema())) {
            throw invalid("Schema '" + schema + "' is the shared schema (sqldao.liquibase.public-schema) "
                    + "and cannot be used as a tenant schema");
        }
    }

    /**
     * Validates a schema name: lower-case, at most 63 characters, not reserved by PostgreSQL.
     *
     * @param schema the schema name
     * @throws MigrationException with {@link MigrationErrorCode#INVALID_CONFIGURATION} if the name is rejected
     */
    public void validateSharedSchemaName(String schema) {
        if (schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
            throw invalid("Invalid schema name '" + schema + "': it must match " + SCHEMA_NAME.pattern());
        }
        if (schema.startsWith(RESERVED_PREFIX)) {
            throw invalid("Invalid schema name '" + schema + "': names starting with pg_ are reserved");
        }
        if (INFORMATION_SCHEMA.equals(schema)) {
            throw invalid("Invalid schema name '" + schema + "': it is reserved by PostgreSQL");
        }
    }

    /**
     * Creates the tenant schema if it does not exist. Callers hold the schema's advisory lock; a concurrent
     * creation that still slips through ({@code 42P06} or {@code 23505}) is treated as success.
     *
     * @param connection the migration connection
     * @param tenantId the tenant identifier
     * @param schema the tenant schema
     * @throws MigrationException with {@link MigrationErrorCode#SCHEMA_CREATION_FAILED} on failure
     */
    public void ensureSchema(Connection connection, String tenantId, String schema) {
        validateSchemaName(schema);
        try {
            if (schemaExists(connection, schema)) {
                LOGGER.debug("Schema {} already exists for tenant {}", schema, tenantId);
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + quote(schema));
            }
            LOGGER.info("Created schema {} for tenant {}", schema, tenantId);
        } catch (SQLException e) {
            if (DUPLICATE_SQL_STATES.contains(e.getSQLState())) {
                LOGGER.info("Schema {} for tenant {} was created concurrently", schema, tenantId);
                return;
            }
            throw new MigrationException("Failed to create schema " + schema + " for tenant " + tenantId,
                    tenantId, MigrationErrorCode.SCHEMA_CREATION_FAILED, e);
        }
    }

    /**
     * Quotes an identifier, doubling any embedded double quote so that it cannot end the identifier early.
     *
     * @param identifier the identifier
     * @return the quoted identifier
     */
    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Builds {@code SET search_path TO "a", "b"}.
     *
     * @param schemas the schema names, in search order
     * @return the {@code SET search_path} statement
     */
    static String setSearchPath(List<String> schemas) {
        return "SET search_path TO " + schemas.stream().map(SchemaProvisioningService::quote)
                .collect(Collectors.joining(", "));
    }

    private static boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SCHEMA_EXISTS_SQL)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static MigrationException invalid(String message) {
        return new MigrationException(message, null, MigrationErrorCode.INVALID_CONFIGURATION);
    }
}
