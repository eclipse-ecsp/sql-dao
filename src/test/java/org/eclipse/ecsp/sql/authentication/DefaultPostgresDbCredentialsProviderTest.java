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

package org.eclipse.ecsp.sql.authentication;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Test for class {@link DefaultPostgresDbCredentialsProvider}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { DefaultPostgresDbCredentialsProvider.class })
@TestPropertySource("/application-dao-test.properties")
@Testcontainers
public class DefaultPostgresDbCredentialsProviderTest {

    /** The default postgres db credentials provider. */
    @Autowired
    DefaultPostgresDbCredentialsProvider defaultPostgresDbCredentialsProvider;

    /** The postgresql container. */
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgresqlContainer = new PostgreSQLContainer<>("postgres:15").withDatabaseName("test")
            .withUsername("root").withPassword("root");

    /**
     * Sets up postgres.
     */
    @BeforeAll
    public static void setUpPostgres() {
        postgresqlContainer.start();
        System.setProperty("DB_URL", postgresqlContainer.getJdbcUrl());
    }

    /**
     * Test get user name.
     */
    @Test
    void testgetUserName() {
        assertNotNull(defaultPostgresDbCredentialsProvider.getUserName());
    }

    /**
     * Test get password.
     */
    @Test
    void testgetPassword() {
        assertNotNull(defaultPostgresDbCredentialsProvider.getPassword());
    }

    /**
     * Test get all credentials config.
     */
    @Test
    void testgetAllCredentialsConfig() {
        assertNotNull(defaultPostgresDbCredentialsProvider.getAllCredentialsConfig());
    }

    /**
     * Test refresh credentials.
     */
    @Test
    void testrefreshCredentials() {
        defaultPostgresDbCredentialsProvider.refreshCredentials();
        assertFalse(defaultPostgresDbCredentialsProvider.isRefreshInProgress());
    }

    /**
     * Tear up postgres server.
     */
    @AfterAll
    public static void tearUpPostgresServer() {
        postgresqlContainer.stop();
    }
}
