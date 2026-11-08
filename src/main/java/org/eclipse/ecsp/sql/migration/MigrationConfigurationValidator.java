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

import liquibase.util.LiquibaseUtil;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.ecsp.sql.dao.constants.MigrationConstants;
import org.eclipse.ecsp.sql.multitenancy.TenantDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Configuration checks that always fail startup, whatever {@code fail-on-error} and {@code startup-mode} are.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class MigrationConfigurationValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationConfigurationValidator.class);
    private static final String SUPPORTED_LIQUIBASE_MAJOR = "4";
    private static final String PINNED_LIQUIBASE_VERSION = "4.33.0";
    private static final Pattern CURRENT_SCHEMA_PARAMETER =
            Pattern.compile("[?&]currentSchema=", Pattern.CASE_INSENSITIVE);

    private final SqlDaoLiquibaseProperties properties;
    private final MigrationDataSourceResolver resolver;
    private final SchemaProvisioningService provisioningService;
    private final Environment environment;

    /**
     * Creates the validator.
     *
     * @param properties the Liquibase migration properties
     * @param resolver the migration datasource resolver
     * @param provisioningService the schema-name validator
     * @param environment the Spring environment
     */
    public MigrationConfigurationValidator(SqlDaoLiquibaseProperties properties, MigrationDataSourceResolver resolver,
            SchemaProvisioningService provisioningService, Environment environment) {
        this.properties = properties;
        this.resolver = resolver;
        this.provisioningService = provisioningService;
        this.environment = environment;
    }

    /**
     * Validates the global configuration, every tenant, and every database.
     *
     * @param tenants the tenants by identifier
     * @throws MigrationException with {@link MigrationErrorCode#INVALID_CONFIGURATION} listing every problem
     */
    public void validate(Map<String, TenantDatabaseProperties> tenants) {
        List<String> errors = new ArrayList<>(validateGlobal());
        new TreeMap<>(tenants).forEach((tenantId, tenant) -> errors.addAll(validateTenant(tenantId, tenant)));
        errors.addAll(validateDatabases(tenants));
        if (!errors.isEmpty()) {
            throw new MigrationException("Invalid Liquibase migration configuration:" + System.lineSeparator()
                    + " - " + String.join(System.lineSeparator() + " - ", errors), null,
                    MigrationErrorCode.INVALID_CONFIGURATION);
        }
    }

    /**
     * Validates one tenant. Used at startup and for each runtime onboarding.
     *
     * @param tenantId the tenant identifier
     * @param tenant the tenant properties
     * @return the problems found, empty when valid
     */
    public List<String> validateTenant(String tenantId, TenantDatabaseProperties tenant) {
        List<String> errors = new ArrayList<>();
        String prefix = "tenants.profile." + tenantId + ": ";
        String schema = tenant.getSchema();
        if (schema != null) {
            addIfInvalid(errors, prefix, () -> this.provisioningService.validateSchemaName(schema));
            if (tenant.getJdbcUrl() != null && CURRENT_SCHEMA_PARAMETER.matcher(tenant.getJdbcUrl()).find()) {
                errors.add(prefix + "jdbc-url must not carry currentSchema when schema is set, because PgJDBC lets URL "
                        + "parameters override the search path that sql-dao sets");
            }
        }
        String alias = StringUtils.trimToNull(tenant.getMigrationAdmin());
        if (alias != null && schema == null) {
            errors.add(prefix + "migration-admin requires a schema; a tenant without schema migrates on its own pool");
        }
        if (alias != null && !this.properties.getAdmin().containsKey(alias)) {
            errors.add(prefix + "migration-admin '" + alias + "' is not defined under sqldao.liquibase.admin");
        }
        return errors;
    }

    /**
     * Checks the Liquibase version on the classpath.
     *
     * @param version the Liquibase build version
     * @return the problem found, or {@code null} when the version is supported
     */
    String checkLiquibaseVersion(String version) {
        String major = StringUtils.substringBefore(StringUtils.defaultString(version), ".");
        if (!StringUtils.isNumeric(major)) {
            LOGGER.warn("Cannot determine the Liquibase version ({}); sql-dao is verified with {}", version,
                    PINNED_LIQUIBASE_VERSION);
            return null;
        }
        if (SUPPORTED_LIQUIBASE_MAJOR.equals(major)) {
            return null;
        }
        return "Liquibase " + version + " is on the classpath, but sql-dao requires " + PINNED_LIQUIBASE_VERSION
                + " (Liquibase 5.x is FSL-licensed). Pin org.liquibase:liquibase-core to " + PINNED_LIQUIBASE_VERSION
                + ": with spring-boot-starter-parent set <liquibase.version>; with an imported Spring Boot BOM declare "
                + "liquibase-core in your own dependencyManagement before the BOM import";
    }

    private List<String> validateGlobal() {
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(this.properties.getChangeLog())) {
            errors.add("sqldao.liquibase.change-log is required when sqldao.liquibase.enabled=true");
        }
        String versionError = checkLiquibaseVersion(LiquibaseUtil.getBuildVersion());
        if (versionError != null) {
            errors.add(versionError);
        }
        if (this.environment.getProperty(MigrationConstants.DEFER_DATASOURCE_INITIALIZATION, Boolean.class, false)) {
            errors.add(MigrationConstants.DEFER_DATASOURCE_INITIALIZATION + "=true is not supported: it makes "
                    + "Hibernate initialize before the Liquibase migrations");
        }
        addIfInvalid(errors, "sqldao.liquibase.public-schema: ",
                () -> this.provisioningService.validateSharedSchemaName(this.properties.getPublicSchema()));
        errors.addAll(validateAdminAliases());
        return errors;
    }

    private List<String> validateAdminAliases() {
        List<String> errors = new ArrayList<>();
        Map<String, String> aliasByDatabase = new HashMap<>();
        new TreeMap<>(this.properties.getAdmin()).forEach((alias, admin) -> {
            if (StringUtils.isBlank(admin.getJdbcUrl())) {
                errors.add("sqldao.liquibase.admin." + alias + ".jdbc-url is required");
                return;
            }
            String other = aliasByDatabase.putIfAbsent(JdbcUrlNormalizer.normalize(admin.getJdbcUrl()), alias);
            if (other != null) {
                errors.add("sqldao.liquibase.admin." + other + " and sqldao.liquibase.admin." + alias
                        + " point to the same database");
            }
        });
        return errors;
    }

    private List<String> validateDatabases(Map<String, TenantDatabaseProperties> tenants) {
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(this.properties.getPublicChangeLog())) {
            return errors;
        }
        for (DatabaseTarget target : this.resolver.distinctDatabases(tenants)) {
            if (target.explicitAdminAliases().size() > 1) {
                errors.add("Tenants " + target.tenantIds() + " of database " + target.key()
                        + " name different migration-admin aliases " + target.explicitAdminAliases());
            } else if (target.adminAlias() == null) {
                errors.add("sqldao.liquibase.public-change-log is set, but no admin alias resolves for database "
                        + target.key() + " (tenants " + target.tenantIds() + "); Phase 1 never runs on a tenant pool");
            }
        }
        return errors;
    }

    private static void addIfInvalid(List<String> errors, String prefix, Runnable check) {
        try {
            check.run();
        } catch (MigrationException e) {
            errors.add(prefix + e.getMessage());
        }
    }
}
