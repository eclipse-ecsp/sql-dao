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

package org.eclipse.ecsp.sql.multitenancy;

import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.eclipse.ecsp.sql.dao.constants.MultitenantConstants;
import org.eclipse.ecsp.sql.postgress.config.PostgresDbConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Component responsible for initializing and configuring tenant-aware data source routing.
 *
 * <p>This class manages the initialization of {@link TenantRoutingDataSource} based on
 * whether multi-tenancy is enabled. In single-tenant mode, it sets up a default data source.
 * In multi-tenant mode, it configures routing to multiple tenant-specific data sources.</p>
 *
 * @author hbadshah
 */
@Component
public class TenantAwareDataSource {

    private static final Logger logger = Logger.getLogger(TenantAwareDataSource.class.getName());

    @Value("${" + MultitenantConstants.MULTITENANCY_ENABLED + ":false}")
    private boolean isMultitenancyEnabled;

    @Autowired
    @Qualifier("targetDataSources")
    private Map<Object, Object> targetDataSources;
    
    @Autowired
    private PostgresDbConfig postgresDbConfig;
    
    private TenantRoutingDataSource tenantRoutingDataSource;

    /**
     * Initializes the tenant routing data source as a Spring Bean.
     *
     * <p>This method creates and configures the {@link TenantRoutingDataSource} with appropriate 
     * target data sources based on whether multi-tenancy is enabled.</p>
     *
     * <p>For single-tenant mode, it sets both the target data sources map and the default
     * data source. For multi-tenant mode, it only sets the target data sources map, allowing
     * dynamic routing based on the current tenant context.</p>
     * 
     * @return Configured DataSource instance
     */
    @Bean
    @Primary
    @DependsOn("targetDataSources")
    public DataSource dataSource() {
        logger.info("Initializing TenantAwareDataSource");
        tenantRoutingDataSource = new TenantRoutingDataSource();
        if (!isMultitenancyEnabled) {
            logger.info("Multitenancy is disabled. Using default tenant data source.");
            tenantRoutingDataSource.setTargetDataSources(targetDataSources);
            tenantRoutingDataSource.setDefaultTargetDataSource(
                    targetDataSources.get(MultitenantConstants.DEFAULT_TENANT_ID));
        } else {
            logger.info("Multitenancy is enabled. Setting target data sources for tenants.");
            tenantRoutingDataSource.setTargetDataSources(targetDataSources);
        }
        tenantRoutingDataSource.afterPropertiesSet();
        logger.info("TenantAwareDataSource initialized successfully.");
        return tenantRoutingDataSource;
    }
    
    /**
     * Adds or updates a single tenant datasource to the routing datasource.
     * 
     * <p>This method provides a way to add or update a single tenant's datasource 
     * dynamically at runtime.</p>
     * 
     * <p><b>Behavior:</b></p>
     * <ul>
     * <li>If the tenant datasource already exists, it will be closed and recreated with the latest configuration</li>
     * <li>If the tenant is new, a new datasource will be created and added to the routing</li>
     * <li>The routing datasource is refreshed to recognize the new/updated tenant</li>
     * </ul>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * &#64;Autowired
     * private TenantAwareDataSource tenantAwareDataSource;
     * 
     * public void addNewTenant(String tenantId, TenantDatabaseProperties tenantProps) {
     *     boolean success = tenantAwareDataSource.addTenantDataSource(tenantId, tenantProps);
     *     if (success) {
     *         logger.info("Tenant datasource added successfully: " + tenantId);
     *     }
     * }
     * </pre>
     * 
     * @param tenantId the ID of the tenant to add or update
     * @param tenantDatabaseProperties the database configuration properties for the tenant
     * @return true if the operation was successful, false if multitenancy is disabled, invalid parameters, or an error occurred
     */
    public boolean addTenantDataSource(String tenantId, TenantDatabaseProperties tenantDatabaseProperties) {
        try {
            logger.info("Adding/updating tenant datasource for: " + tenantId);
            
            if (!isMultitenancyEnabled) {
                logger.warning("Multitenancy is disabled. Cannot add tenant datasource.");
                return false;
            }
            
            if (tenantId == null || tenantId.trim().isEmpty()) {
                logger.warning("Invalid tenant ID provided. Cannot add tenant datasource.");
                return false;
            }
            
            if (tenantDatabaseProperties == null) {
                logger.warning("Tenant database properties cannot be null for tenant: " + tenantId);
                return false;
            }
            
            tenantId = tenantId.trim();
            
            // Check if tenant datasource already exists
            DataSource existingDataSource = (DataSource) targetDataSources.get(tenantId);
            if (existingDataSource != null) {
                logger.info("Tenant datasource already exists for: " + tenantId + ". Recreating...");
                
                // Close existing datasource
                if (existingDataSource instanceof HikariDataSource) {
                    ((HikariDataSource) existingDataSource).close();
                    logger.info("Closed existing HikariDataSource for tenant: " + tenantId);
                }
            }
            
            // Create new datasource directly using the provided properties
            DataSource newDataSource = postgresDbConfig.createAndGetDataSource(tenantDatabaseProperties);
            if (newDataSource == null) {
                logger.severe("Failed to create datasource for tenant: " + tenantId);
                return false;
            }
            
            // Add to target datasources
            targetDataSources.put(tenantId, newDataSource);
            logger.info("Successfully added tenant datasource to map: " + tenantId);
            
            // Refresh routing datasource
            if (tenantRoutingDataSource != null) {
                tenantRoutingDataSource.setTargetDataSources(targetDataSources);
                tenantRoutingDataSource.afterPropertiesSet();
                logger.info("Tenant routing datasource refreshed successfully.");
            }
            
            logger.info("Successfully added/updated datasource for tenant: " + tenantId);
            return true;
            
        } catch (Exception e) {
            logger.severe("Error adding tenant datasource for " + tenantId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
