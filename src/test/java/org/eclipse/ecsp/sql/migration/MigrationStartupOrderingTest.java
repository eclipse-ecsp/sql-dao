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
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Startup ordering: the coordinator is a database initializer, so JDBC templates and
 * {@code @DependsOnDatabaseInitialization} beans are created after it.
 */
class MigrationStartupOrderingTest {

    @Test
    void detectorReportsCoordinatorBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        assertTrue(new MigrationDatabaseInitializerDetector().detect(beanFactory).isEmpty());
        beanFactory.registerBeanDefinition("coordinator", new RootBeanDefinition(MigrationStartupCoordinator.class));
        assertEquals(Set.of("coordinator"), new MigrationDatabaseInitializerDetector().detect(beanFactory));
    }

    @Test
    void detectorIsRegisteredInSpringFactories() throws IOException {
        String factories = new ClassPathResource("META-INF/spring.factories", getClass().getClassLoader())
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(factories.contains(MigrationDatabaseInitializerDetector.class.getName()));
    }

    @Test
    void jdbcTemplatesAndAnnotatedBeansDependOnCoordinator() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // The coordinator is conditional on sqldao.liquibase.enabled=true.
            TestPropertyValues.of("sqldao.liquibase.enabled=true").applyTo(context);
            context.registerBean("migrationStartupCoordinator", MigrationStartupCoordinator.class,
                    () -> mock(MigrationStartupCoordinator.class));
            context.register(OrderingConfiguration.class);
            context.refresh();
            assertDependsOnCoordinator(context, "jdbcTemplate");
            assertDependsOnCoordinator(context, "jdbcTemplates");
        }
    }

    private static void assertDependsOnCoordinator(AnnotationConfigApplicationContext context, String beanName) {
        String[] dependsOn = context.getBeanFactory().getBeanDefinition(beanName).getDependsOn();
        assertTrue(dependsOn != null && Arrays.asList(dependsOn).contains("migrationStartupCoordinator"),
                beanName + " must depend on the migration coordinator");
    }

    /**
     * Mirrors {@link LiquibaseMigrationConfiguration} and the library's template beans.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(DatabaseInitializationDependencyConfigurer.class)
    static class OrderingConfiguration {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(mock(DataSource.class));
        }

        @Bean
        @DependsOnDatabaseInitialization
        Map<String, JdbcTemplate> jdbcTemplates() {
            return Map.of();
        }
    }
}
