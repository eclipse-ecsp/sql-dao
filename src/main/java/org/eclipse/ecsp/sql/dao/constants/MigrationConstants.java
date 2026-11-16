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

package org.eclipse.ecsp.sql.dao.constants;

/**
 * Constants for Liquibase migration support.
 */
public final class MigrationConstants {

    /** Prefix of all Liquibase migration properties. */
    public static final String LIQUIBASE_PREFIX = "sqldao.liquibase";

    /** Master switch for Liquibase migration support. */
    public static final String LIQUIBASE_ENABLED = LIQUIBASE_PREFIX + ".enabled";

    /** Shared schema targeted by Phase 1 and placed after the tenant schema on tenant search paths. */
    public static final String LIQUIBASE_PUBLIC_SCHEMA = LIQUIBASE_PREFIX + ".public-schema";

    /** Default shared schema. */
    public static final String DEFAULT_PUBLIC_SCHEMA = "public";

    /** Spring Boot property that must stay {@code false} while Liquibase migration support is enabled. */
    public static final String DEFER_DATASOURCE_INITIALIZATION = "spring.jpa.defer-datasource-initialization";

    /**
     * Private constructor for migration constants.
     */
    private MigrationConstants() {
        throw new UnsupportedOperationException("MigrationConstants is a constants class and cannot be instantiated");
    }
}
