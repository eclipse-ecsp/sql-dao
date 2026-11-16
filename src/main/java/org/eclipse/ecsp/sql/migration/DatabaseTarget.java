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

/**
 * A distinct database that Phase 1 migrates once, with the tenants that live in it.
 *
 * @param key normalized JDBC URL ({@code host:port/database})
 * @param jdbcUrl JDBC URL of one of its tenants
 * @param tenantIds tenants in this database, sorted
 * @param adminAlias admin alias resolved for Phase 1, or {@code null} when none resolves
 */
public record DatabaseTarget(String key, String jdbcUrl, List<String> tenantIds,
                             String adminAlias) {
}
