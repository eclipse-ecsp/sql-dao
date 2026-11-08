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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link JdbcUrlNormalizer}.
 */
class JdbcUrlNormalizerTest {

    @Test
    void stripsQueryParametersAndLowerCasesHost() {
        assertEquals("db-host:5432/appdb",
                JdbcUrlNormalizer.normalize("jdbc:postgresql://DB-Host:5432/appdb?sslmode=require&user=x"));
    }

    @Test
    void defaultsMissingPort() {
        assertEquals("db-host:5432/appdb", JdbcUrlNormalizer.normalize("jdbc:postgresql://db-host/appdb"));
    }

    @Test
    void sortsMultipleHosts() {
        assertEquals("h1:5432,h2:5433/appdb", JdbcUrlNormalizer.normalize("jdbc:postgresql://H2:5433,h1/appdb"));
    }

    @Test
    void keepsBracketedIpv6Hosts() {
        assertEquals("[::1]:5433/appdb", JdbcUrlNormalizer.normalize("jdbc:postgresql://[::1]:5433/appdb"));
        assertEquals("[::1]:5432/appdb", JdbcUrlNormalizer.normalize("jdbc:postgresql://[::1]/appdb"));
    }

    @Test
    void defaultsHostForUrlWithoutAuthority() {
        assertEquals("localhost:5432/appdb", JdbcUrlNormalizer.normalize("jdbc:postgresql:appdb"));
    }

    @Test
    void keepsDatabaseNameCase() {
        assertEquals("db-host:5432/AppDb", JdbcUrlNormalizer.normalize("jdbc:postgresql://db-host/AppDb"));
    }

    @Test
    void returnsEmptyForBlankUrl() {
        assertEquals("", JdbcUrlNormalizer.normalize(null));
        assertEquals("", JdbcUrlNormalizer.normalize(" "));
    }

    @Test
    void differentSpellingsOfOneDatabaseMatch() {
        assertEquals(JdbcUrlNormalizer.normalize("jdbc:postgresql://db-host/appdb"),
                JdbcUrlNormalizer.normalize("JDBC:POSTGRESQL://DB-HOST:5432/appdb?currentSchema=x"));
    }
}
