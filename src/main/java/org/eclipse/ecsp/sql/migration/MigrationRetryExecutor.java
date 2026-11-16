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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retries one migration attempt when it fails, whatever the reason.
 *
 * <p>An attempt must be the whole unit of work: borrow a connection, take the schema's advisory lock, migrate, and
 * release both. Each retry then starts from a fresh connection and a fresh lock, and the wait between attempts holds
 * neither. Re-running is safe because Liquibase records applied changesets and continues with the pending ones.
 *
 * <p>Only an interrupted thread is never retried, so that shutdown is not held up.
 */
@Component
@ConditionalOnLiquibaseEnabled
public class MigrationRetryExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationRetryExecutor.class);

    private final SqlDaoLiquibaseProperties properties;

    /**
     * Creates the executor.
     *
     * @param properties the Liquibase migration properties
     */
    public MigrationRetryExecutor(SqlDaoLiquibaseProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs the attempt, repeating it after a {@link MigrationException} until it succeeds or
     * {@code sqldao.liquibase.retry.max-attempts} is used up.
     *
     * @param target what is being migrated, for log messages
     * @param attempt one complete migration attempt
     * @throws MigrationException the failure of the last attempt
     */
    public void run(String target, Runnable attempt) {
        MigrationRetryProperties retry = this.properties.getRetry();
        int maxAttempts = Math.max(1, retry.getMaxAttempts());
        for (int attemptNumber = 1; ; attemptNumber++) {
            try {
                attempt.run();
                return;
            } catch (MigrationException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                if (attemptNumber >= maxAttempts) {
                    if (maxAttempts > 1) {
                        LOGGER.warn("Giving up on {} after {} attempts", target, attemptNumber);
                    }
                    throw e;
                }
                LOGGER.warn("Migration of {} failed on attempt {} of {}; retrying in {} ms: {}",
                        target, attemptNumber, maxAttempts, retry.getDelayMs(), e.getMessage());
                pause(target, retry.getDelayMs(), e);
            }
        }
    }

    private static void pause(String target, long delayMs, MigrationException failure) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MigrationException interrupted = new MigrationException(
                    "Interrupted while waiting to retry the migration of " + target, failure.getTenantId(),
                    MigrationErrorCode.MIGRATION_FAILED, e);
            interrupted.addSuppressed(failure);
            throw interrupted;
        }
    }
}
