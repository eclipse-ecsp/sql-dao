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

import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL identifier helpers. Callers pass only names that passed schema-name validation.
 */
final class SqlIdentifiers {

    private SqlIdentifiers() {
        throw new UnsupportedOperationException("SqlIdentifiers is a utility class and cannot be instantiated");
    }

    /**
     * Quotes a validated identifier.
     *
     * @param identifier a validated lower-case identifier
     * @return the quoted identifier
     */
    static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /**
     * Builds {@code SET search_path TO "a", "b"} for validated schema names.
     *
     * @param schemas validated schema names, in search order
     * @return the {@code SET search_path} statement
     */
    static String setSearchPath(List<String> schemas) {
        return "SET search_path TO " + schemas.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
    }
}
