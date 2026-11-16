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

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.sql.init.dependency.DatabaseInitializerDetector;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reports {@link MigrationStartupCoordinator} as a database initializer, so that Spring Boot makes the
 * {@code EntityManagerFactory}, JDBC templates, and {@code @DependsOnDatabaseInitialization} beans depend on it.
 *
 * <p>Registered in {@code META-INF/spring.factories}.
 */
public class MigrationDatabaseInitializerDetector implements DatabaseInitializerDetector {

    /**
     * Detects the migration coordinator bean, if Liquibase migration support is enabled.
     *
     * @param beanFactory the bean factory
     * @return the coordinator bean names
     */
    @Override
    public Set<String> detect(ConfigurableListableBeanFactory beanFactory) {
        return new LinkedHashSet<>(Arrays.asList(
                beanFactory.getBeanNamesForType(MigrationStartupCoordinator.class, false, false)));
    }
}
