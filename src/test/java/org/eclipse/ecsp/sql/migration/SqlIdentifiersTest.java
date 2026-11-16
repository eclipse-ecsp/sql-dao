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
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link SqlIdentifiers}.
 */
class SqlIdentifiersTest {

    @Test
    void quotesIdentifiers() {
        assertEquals("\"tenant_1\"", SqlIdentifiers.quote("tenant_1"));
        assertEquals("SET search_path TO \"tenant_1\", \"public\"",
                SqlIdentifiers.setSearchPath(List.of("tenant_1", "public")));
    }

    @Test
    void escapesEmbeddedQuotesSoNameCannotEndIdentifierEarly() {
        assertEquals("\"a\"\"; DROP SCHEMA x; --\"", SqlIdentifiers.quote("a\"; DROP SCHEMA x; --"));
        assertEquals("SET search_path TO \"a\"\"b\", \"public\"",
                SqlIdentifiers.setSearchPath(List.of("a\"b", "public")));
    }
}
