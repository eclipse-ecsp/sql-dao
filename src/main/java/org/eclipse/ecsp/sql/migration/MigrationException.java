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
import org.eclipse.ecsp.sql.exception.SqlDaoException;

/**
 * Migration failure carrying the affected tenant and an error code. Messages never include credentials.
 */
@Getter
public class MigrationException extends SqlDaoException {

    private static final long serialVersionUID = 1L;

    /** Affected tenant, or {@code null} for database-level and configuration failures. */
    private final String tenantId;

    /** Failure classification. */
    private final MigrationErrorCode errorCode;

    /**
     * Creates a migration exception.
     *
     * @param message the failure description
     * @param tenantId the affected tenant, or {@code null}
     * @param errorCode the failure classification
     */
    public MigrationException(String message, String tenantId, MigrationErrorCode errorCode) {
        super(message);
        this.tenantId = tenantId;
        this.errorCode = errorCode;
    }

    /**
     * Creates a migration exception with a cause.
     *
     * @param message the failure description
     * @param tenantId the affected tenant, or {@code null}
     * @param errorCode the failure classification
     * @param cause the underlying failure
     */
    public MigrationException(String message, String tenantId, MigrationErrorCode errorCode, Throwable cause) {
        super(message, cause);
        this.tenantId = tenantId;
        this.errorCode = errorCode;
    }

    /**
     * Classifies a {@link MigrationException}.
     */
    public enum MigrationErrorCode {

        /** The migration configuration is invalid; always fails startup. */
        INVALID_CONFIGURATION,

        /** A Liquibase update failed, or the tenant could not be migrated. */
        MIGRATION_FAILED,

        /** A tenant schema could not be created. */
        SCHEMA_CREATION_FAILED,

        /** A Liquibase rollback failed. */
        ROLLBACK_FAILED,

        /** The per-schema advisory lock could not be acquired in time. */
        LOCK_ACQUISITION_FAILED
    }
}
