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
