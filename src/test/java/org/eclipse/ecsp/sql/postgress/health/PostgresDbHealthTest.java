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

package org.eclipse.ecsp.sql.postgress.health;

import org.eclipse.ecsp.sql.dao.constants.CredentialsConstants;
import org.eclipse.ecsp.sql.dao.constants.HealthConstants;
import org.eclipse.ecsp.sql.dao.constants.MetricsConstants;
import org.eclipse.ecsp.sql.dao.constants.PostgresDbConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * class {@link PostgresDbHealthTest}
 *      checks the heath of Postgress Database.
 */
class PostgresDbHealthTest {

    /** The postgres db health monitor. */
    PostgresDbHealthMonitor postgresDbHealthMonitor = new PostgresDbHealthMonitor();

    /** The postgres db health check. */
    PostgresDbHealthCheck postgresDbHealthCheck = new PostgresDbHealthCheck();

    /**
     * Test check metrics values.
     *
     * @throws Exception the exception
     */
    @Test
    void testcheckMetricsValues() throws Exception {
        assertEquals("metrics.prometheus.enabled", MetricsConstants.PROMETHEUS_ENABLED);
        assertEquals("prometheus.agent.port", MetricsConstants.PROMETHEUS_AGENT_PORT_KEY);
        assertEquals("prometheus.agent.port.exposed", MetricsConstants.PROMETHEUS_AGENT_PORT_EXPOSED);
        assertEquals("postgresdb.metrics.enabled", MetricsConstants.POSTGRES_DB_METRICS_ENABLED);
        assertEquals("postgresdb.metrics.thread.initial.delay.ms",
                MetricsConstants.POSTGRES_DB_METRICS_THREAD_INITIAL_DELAY_MS);
        assertEquals("postgresdb.metrics.thread.freq.ms", MetricsConstants.POSTGRES_DB_METRICS_THREAD_FREQUENCY_MS);
        assertEquals("postgresdb.metrics.executor.shutdown.buffer.ms",
                MetricsConstants.POSTGRES_DB_METRICS_EXECUTOR_SHUTDOWN_BUFFER_MS);
        assertEquals("service.name", MetricsConstants.SERVICE_NAME);
        assertEquals(".pool.TotalConnections", MetricsConstants.POSTGRES_METRIC_TOTAL_CONNECTIONS);
        assertEquals(".pool.IdleConnections", MetricsConstants.POSTGRES_METRIC_IDLE_CONNECTIONS);
        assertEquals(".pool.ActiveConnections", MetricsConstants.POSTGRES_METRIC_ACTIVE_CONNECTIONS);
        assertEquals(".pool.PendingConnections", MetricsConstants.POSTGRES_METRIC_PENDING_CONNECTIONS);
    }

    /**
     * Test check postgres values.
     *
     * @throws Exception the exception
     */
    @Test
    void testcheckPostgresValues() throws Exception {
        assertEquals("postgres.jdbc.url", PostgresDbConstants.POSTGRES_JDBC_URL);
        assertEquals("postgres.username", PostgresDbConstants.POSTGRES_USERNAME);
        assertEquals("postgres.password", PostgresDbConstants.POSTGRES_PASSWORD);
        assertEquals("postgres.driver.class.name", PostgresDbConstants.POSTGRES_DRIVER_CLASS_NAME);
        assertEquals("postgres.pool.name", PostgresDbConstants.POSTGRES_POOL_NAME);
        assertEquals("postgres.min.pool.size", PostgresDbConstants.POSTGRES_MIN_POOL_SIZE);
        assertEquals("postgres.max.pool.size", PostgresDbConstants.POSTGRES_MAX_POOL_SIZE);
        assertEquals("postgres.max.idle.time", PostgresDbConstants.POSTGRES_MAX_IDLE_TIME);
        assertEquals("postgres.connection.timeout.ms", PostgresDbConstants.POSTGRES_CONNECTION_TIMEOUT_MS);
        assertEquals("cachePrepStmts", PostgresDbConstants.POSTGRES_DS_CACHE_PREPARED_STATEMENTS);
        assertEquals("prepStmtCacheSize", PostgresDbConstants.POSTGRES_DS_PREPARED_STATEMENT_CACHE_SIZE);
        assertEquals("prepStmtCacheSqlLimit", PostgresDbConstants.POSTGRES_DS_PREPARED_STATEMENT_CACHE_SQL_LIMIT);
        assertEquals("postgres.refreshCheckInterval", PostgresDbConstants.POSTGRES_REFRESH_CHECK_INTERVAL);
        assertEquals("postgres.credentials.refresh.enabled", PostgresDbConstants.POSTGRES_CREDENTIALS_REFRESH_ENABLED);
        assertEquals("postgres.data-source-properties.cachePrepStmts",
                PostgresDbConstants.POSTGRES_DS_CACHE_PREPARED_STATEMENTS_VALUE);
        assertEquals("postgres.data-source-properties.prepStmtCacheSize",
                PostgresDbConstants.POSTGRES_DS_PREPARED_STATEMENT_CACHE_SIZE_VALUE);
        assertEquals("postgres.data-source-properties.prepStmtCacheSqlLimit",
                PostgresDbConstants.POSTGRES_DS_PREPARED_STATEMENT_CACHE_SQL_LIMIT_VALUE);
        assertEquals("", PostgresDbConstants.PROMETHEUS_ENABLED);
    }
    
    /**
     * Test check healt values.
     *
     * @throws Exception the exception
     */
    @Test
    void testcheckHealtValues() throws Exception  {
        assertEquals("health.postgresdb.monitor.enabled", HealthConstants.HEALTH_POSTGRES_DB_MONITOR_ENABLED);
        assertEquals("health.postgresdb.monitor.restart.on.failure",
                HealthConstants.HEALTH_POSTGRES_DB_MONITOR_RESTART_ON_FAILURE);
        assertEquals("POSTGRESDB_HEALTH_MONITOR", HealthConstants.HEALTH_POSTGRES_DB_MONITOR_NAME);
        assertEquals("POSTGRESDB_HEALTH_GUAGE", HealthConstants.HEALTH_POSTGRES_DB_MONTIOR_GUAGE);
        assertEquals(".pool.ConnectivityCheck", HealthConstants.POOL_CONNECTIVITY_HEALTH_CHECK);
        assertEquals(".pool.Connection99Percent", HealthConstants.POOL_CONNECTION_99_PERCENT_HEALTH_CHECK);
        assertEquals("expected99thPercentileMs", HealthConstants.POSTGRES_EXPECTED_99_PI_MS);
        assertEquals("postgres.expected99thPercentileMs", HealthConstants.POSTGRES_EXPECTED_99_PI_MS_VALUE);
    }
    
    /**
     * Test constant values.
     *
     * @throws Exception the exception
     */
    @Test
    void testcheck4() throws Exception  {
        assertEquals("userName", CredentialsConstants.USERNAME_FIELD);
        assertEquals("password", CredentialsConstants.PASSWORD_FIELD);
        assertEquals("leaseDuration", CredentialsConstants.LEASE_DURATION_FIELD);
        assertEquals("credential.provider.bean.name", CredentialsConstants.CREDENTIAL_PROVIDER_BEAN_NAME);
    }
}
