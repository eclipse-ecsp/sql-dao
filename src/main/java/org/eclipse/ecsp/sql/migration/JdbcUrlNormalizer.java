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

import org.apache.commons.lang3.StringUtils;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Reduces a PostgreSQL JDBC URL to {@code host:port/database}, so that tenants of one database can be grouped.
 *
 * <p>Query parameters are stripped, hosts are lower-cased, a missing port defaults to {@code 5432}, and
 * multi-host URLs use the sorted host list. De-duplication is an optimization: two URLs for the same database
 * that normalize differently only cause a second, no-op Phase 1 run.
 */
public final class JdbcUrlNormalizer {

    private static final String POSTGRES_PREFIX = "jdbc:postgresql:";
    private static final String AUTHORITY_PREFIX = "//";
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "5432";

    private JdbcUrlNormalizer() {
        throw new UnsupportedOperationException("JdbcUrlNormalizer is a utility class and cannot be instantiated");
    }

    /**
     * Normalizes a PostgreSQL JDBC URL.
     *
     * @param jdbcUrl the JDBC URL, may be {@code null}
     * @return {@code host:port[,host:port...]/database}, or an empty string for a blank URL
     */
    public static String normalize(String jdbcUrl) {
        if (StringUtils.isBlank(jdbcUrl)) {
            return "";
        }
        String url = StringUtils.substringBefore(jdbcUrl.trim(), "?");
        if (!url.regionMatches(true, 0, POSTGRES_PREFIX, 0, POSTGRES_PREFIX.length())) {
            return url.toLowerCase(Locale.ROOT);
        }
        String rest = url.substring(POSTGRES_PREFIX.length());
        if (!rest.startsWith(AUTHORITY_PREFIX)) {
            return DEFAULT_HOST + ":" + DEFAULT_PORT + "/" + StringUtils.removeStart(rest, "/");
        }
        String authority = rest.substring(AUTHORITY_PREFIX.length());
        String hosts = StringUtils.substringBefore(authority, "/");
        String database = authority.contains("/") ? StringUtils.substringAfter(authority, "/") : "";
        return normalizeHosts(hosts) + "/" + database;
    }

    private static String normalizeHosts(String hosts) {
        String normalized = Arrays.stream(hosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(JdbcUrlNormalizer::normalizeHost)
                .sorted()
                .collect(Collectors.joining(","));
        return normalized.isEmpty() ? DEFAULT_HOST + ":" + DEFAULT_PORT : normalized;
    }

    private static String normalizeHost(String hostAndPort) {
        String value = hostAndPort.toLowerCase(Locale.ROOT);
        // IPv6 hosts are bracketed; look for the port separator after the closing bracket.
        int searchFrom = value.startsWith("[") ? Math.max(value.indexOf(']'), 0) : 0;
        int portSeparator = value.indexOf(':', searchFrom);
        if (portSeparator < 0) {
            return value + ":" + DEFAULT_PORT;
        }
        String host = value.substring(0, portSeparator);
        String port = value.substring(portSeparator + 1);
        return host + ":" + (port.isEmpty() ? DEFAULT_PORT : port);
    }
}
