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

import org.eclipse.ecsp.sql.migration.MigrationException.MigrationErrorCode;
import org.eclipse.ecsp.sql.migration.SqlDaoLiquibaseProperties.MigrationRetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MigrationRetryExecutor}.
 */
class MigrationRetryExecutorTest {

    private static final int THREE_ATTEMPTS = 3;
    private static final long DELAY_MS = 40L;
    private static final long LONG_DELAY_MS = 30_000L;

    private SqlDaoLiquibaseProperties properties;
    private MigrationRetryExecutor executor;
    private AtomicInteger attempts;

    @BeforeEach
    void setUp() {
        properties = new SqlDaoLiquibaseProperties();
        properties.getRetry().setDelayMs(0);
        executor = new MigrationRetryExecutor(properties);
        attempts = new AtomicInteger();
    }

    @Test
    void defaultsToThreeAttempts() {
        assertEquals(THREE_ATTEMPTS, new MigrationRetryProperties().getMaxAttempts());
    }

    @Test
    void runsOnceWhenTheFirstAttemptSucceeds() {
        executor.run("tenant t1", attempts::incrementAndGet);
        assertEquals(1, attempts.get());
    }

    @Test
    void retriesFailuresUntilAnAttemptSucceeds() {
        executor.run("tenant t1", () -> {
            if (attempts.incrementAndGet() < THREE_ATTEMPTS) {
                throw failure("08006");
            }
        });
        assertEquals(THREE_ATTEMPTS, attempts.get());
    }

    @Test
    void throwsTheLastFailureWhenAttemptsAreUsedUp() {
        MigrationException last = assertThrows(MigrationException.class, () -> executor.run("tenant t1", () -> {
            throw failure("40P01", "attempt " + attempts.incrementAndGet());
        }));
        assertEquals(THREE_ATTEMPTS, attempts.get());
        assertEquals("attempt " + THREE_ATTEMPTS, last.getMessage());
    }

    @Test
    void oneAttemptDisablesRetries() {
        properties.getRetry().setMaxAttempts(1);
        assertThrows(MigrationException.class, () -> executor.run("tenant t1", () -> {
            attempts.incrementAndGet();
            throw failure("08006");
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void retriesEveryKindOfMigrationFailure() {
        MigrationException[] failures = {
            failure("42601"),
            failure("42501"),
            new MigrationException("bad config", null, MigrationErrorCode.INVALID_CONFIGURATION),
            new MigrationException("lock timeout", "t1", MigrationErrorCode.LOCK_ACQUISITION_FAILED),
            new MigrationException("no cause", "t1", MigrationErrorCode.MIGRATION_FAILED),
            new MigrationException("schema", "t1", MigrationErrorCode.SCHEMA_CREATION_FAILED)
        };
        for (MigrationException failure : failures) {
            attempts.set(0);
            MigrationException thrown = assertThrows(MigrationException.class, () -> executor.run("tenant t1", () -> {
                attempts.incrementAndGet();
                throw failure;
            }));
            assertSame(failure, thrown);
            assertEquals(THREE_ATTEMPTS, attempts.get(), failure.getMessage());
        }
    }

    @Test
    void doesNotRetryExceptionsThatAreNotMigrationFailures() {
        assertThrows(IllegalStateException.class, () -> executor.run("tenant t1", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("bug");
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void waitsTheConfiguredDelayBetweenAttempts() {
        properties.getRetry().setDelayMs(DELAY_MS);
        long start = System.nanoTime();
        assertThrows(MigrationException.class, () -> executor.run("tenant t1", () -> {
            throw failure("08006");
        }));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= (THREE_ATTEMPTS - 1) * DELAY_MS, "waited only " + elapsedMs + " ms");
    }

    @Test
    void anInterruptedThreadIsNotRetried() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(MigrationException.class, () -> executor.run("tenant t1", () -> {
                attempts.incrementAndGet();
                throw failure("08006");
            }));
            assertEquals(1, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionDuringTheWaitStopsRetryingAndKeepsTheInterruptFlag() {
        properties.getRetry().setDelayMs(LONG_DELAY_MS);
        Thread testThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            testThread.interrupt();
        });
        interrupter.start();
        try {
            MigrationException exception = assertThrows(MigrationException.class,
                    () -> executor.run("tenant t1", () -> {
                        attempts.incrementAndGet();
                        throw failure("08006");
                    }));
            assertEquals(1, attempts.get());
            assertInstanceOf(InterruptedException.class, exception.getCause());
            assertEquals(1, exception.getSuppressed().length);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static MigrationException failure(String sqlState) {
        return failure(sqlState, "failure " + sqlState);
    }

    private static MigrationException failure(String sqlState, String message) {
        return new MigrationException(message, "t1", MigrationErrorCode.MIGRATION_FAILED,
                new SQLException("sql failure", sqlState));
    }
}
