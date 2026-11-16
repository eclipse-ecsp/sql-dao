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
import org.eclipse.ecsp.sql.dao.constants.PostgresDbConstants;

/**
 * Privileged datasource used to run migrations, bound from {@code sqldao.liquibase.admin.<alias>.*}.
 */
@Getter
@Setter
public class AdminDataSourceProperties {

    /** Default maximum pool size; covers sequential migrations. Raised to {@code sqldao.liquibase.parallelism}. */
    public static final int DEFAULT_MAX_POOL_SIZE = 2;

    /** JDBC URL of the database this admin user migrates. */
    private String jdbcUrl;

    /** Admin user name, used when no credentials provider is configured. */
    private String userName;

    /** Admin password, used when no credentials provider is configured. */
    private String password;

    /** Optional {@code CredentialsProvider} class used instead of {@code user-name}/{@code password}. */
    private String credentialProviderBeanName;

    /** JDBC driver class name. */
    private String driverClassName = PostgresDbConstants.DEFAULT_DRIVER_CLASS_NAME;

    /** Minimum idle connections; admin pools keep none by default. */
    private int minPoolSize;

    /** Maximum pool size; the effective size is at least {@code sqldao.liquibase.parallelism}. */
    private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
}
