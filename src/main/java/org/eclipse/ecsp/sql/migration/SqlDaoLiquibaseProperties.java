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

import lombok.Getter;
import lombok.Setter;
import org.eclipse.ecsp.sql.dao.constants.MigrationConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Liquibase migration properties, bound from {@code sqldao.liquibase.*}.
 *
 * <p>Named to avoid confusion with Spring Boot's own {@code LiquibaseProperties}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = MigrationConstants.LIQUIBASE_PREFIX)
public class SqlDaoLiquibaseProperties {

    /** Default wait for a schema's advisory lock, in seconds. */
    public static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 300;

    /** Default wait for a held {@code DATABASECHANGELOGLOCK}, in minutes. */
    public static final int DEFAULT_CHANGELOG_LOCK_WAIT_MINUTES = 5;

    /** Default number of tenants migrated at the same time; one migrates tenants sequentially. */
    public static final int DEFAULT_PARALLELISM = 1;

    /** Master switch. */
    private boolean enabled;

    /** Tenant changelog on the consuming application's classpath; required when enabled. */
    private String changeLog;

    /** Shared/infrastructure changelog; Phase 1 is skipped when unset. */
    private String publicChangeLog;

    /** Target schema for Phase 1, also placed after the tenant schema on tenant search paths. */
    private String publicSchema = MigrationConstants.DEFAULT_PUBLIC_SCHEMA;

    /** Run Phases 1 to 3 at startup. */
    private boolean runOnStartup = true;

    /** When startup migrations run. */
    private StartupMode startupMode = StartupMode.BLOCKING;

    /** Whether a migration failure aborts startup or a runtime onboarding. */
    private boolean failOnError = true;

    /** How long to wait for a schema's advisory lock, in seconds. */
    private int lockTimeoutSeconds = DEFAULT_LOCK_TIMEOUT_SECONDS;

    /**
     * How many tenants are migrated at the same time at startup (Phases 2 and 3). Phase 1 and runtime onboarding
     * are unaffected. Each running migration holds one connection, so admin pools are sized to at least this value.
     */
    private int parallelism = DEFAULT_PARALLELISM;

    /** Release a LOCKED DATABASECHANGELOGLOCK row as stale while holding the schema's advisory lock. */
    private boolean releaseStaleLocks = true;

    /** How long Liquibase waits for a held DATABASECHANGELOGLOCK before failing, in minutes. */
    private int changelogLockWaitMinutes = DEFAULT_CHANGELOG_LOCK_WAIT_MINUTES;

    /** Retry of a failed migration of one schema. */
    private MigrationRetryProperties retry = new MigrationRetryProperties();

    /** Privileged datasources by alias. */
    private Map<String, AdminDataSourceProperties> admin = new HashMap<>();
}
