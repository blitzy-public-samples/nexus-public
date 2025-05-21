/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.internal.capability.upgrade;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.internal.capability.storage.CapabilityStorage;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItem;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Migration step to add default timeout values to rapture.settings capability.
 * Uses Java 21 Virtual Threads for improved performance during database operations.
 */
@Named
@Singleton
public class SettingsCapabilityMigrationStep_1_28
    implements DatabaseMigrationStep
{
  private static final Logger log = LoggerFactory.getLogger(SettingsCapabilityMigrationStep_1_28.class);
  
  // Constants for capability properties
  private static final String CAPABILITY_TYPE_RAPTURE_SETTINGS = "rapture.settings";
  private static final String PROPERTY_REQUEST_TIMEOUT = "requestTimeout";
  private static final String PROPERTY_LONG_REQUEST_TIMEOUT = "longRequestTimeout";
  private static final String DEFAULT_REQUEST_TIMEOUT = "60";
  private static final String DEFAULT_LONG_REQUEST_TIMEOUT = "180";

  private final CapabilityStorage capabilityStorage;

  @Inject
  public SettingsCapabilityMigrationStep_1_28(final CapabilityStorage capabilityStorage) {
    this.capabilityStorage = checkNotNull(capabilityStorage);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.28");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    log.info(STR."Starting migration step \{version().orElse("unknown")} to add default timeout values");
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Boolean> migrationTask = executor.submit(() -> {
        boolean migrationPerformed = false;
        
        try {
          // Find the rapture.settings capability
          Optional<Map.Entry<String, CapabilityStorageItem>> raptureSettingsCapability = capabilityStorage.getAll().entrySet().stream()
              .filter(entry -> CAPABILITY_TYPE_RAPTURE_SETTINGS.equals(entry.getValue().getType()))
              .findFirst();
          
          // Update the capability if found
          if (raptureSettingsCapability.isPresent()) {
            Map.Entry<String, CapabilityStorageItem> entry = raptureSettingsCapability.get();
            String capabilityId = entry.getKey();
            CapabilityStorageItem capabilityItem = entry.getValue();
            Map<String, String> properties = capabilityItem.getProperties();
            
            // Track if any changes were made
            boolean modified = false;
            
            // Add default request timeout if not present
            if (!properties.containsKey(PROPERTY_REQUEST_TIMEOUT)) {
              properties.put(PROPERTY_REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
              log.debug(STR."Added default \{PROPERTY_REQUEST_TIMEOUT} value: \{DEFAULT_REQUEST_TIMEOUT}");
              modified = true;
            }
            
            // Add default long request timeout if not present
            if (!properties.containsKey(PROPERTY_LONG_REQUEST_TIMEOUT)) {
              properties.put(PROPERTY_LONG_REQUEST_TIMEOUT, DEFAULT_LONG_REQUEST_TIMEOUT);
              log.debug(STR."Added default \{PROPERTY_LONG_REQUEST_TIMEOUT} value: \{DEFAULT_LONG_REQUEST_TIMEOUT}");
              modified = true;
            }
            
            // Update the capability if changes were made
            if (modified) {
              capabilityStorage.update(capabilityId, capabilityItem);
              log.info(STR."Updated rapture.settings capability (ID: \{capabilityId}) with default timeout values");
              migrationPerformed = true;
            } else {
              log.info("No changes needed for rapture.settings capability, timeout values already present");
            }
          } else {
            log.info("No rapture.settings capability found, skipping migration");
          }
        } catch (Exception e) {
          log.error(STR."Error updating rapture.settings capability: \{e.getMessage()}", e);
          throw e;
        }
        
        return migrationPerformed;
      });
      
      try {
        boolean result = migrationTask.get();
        log.info(STR."Migration step \{version().orElse("unknown")} completed successfully. Changes applied: \{result}");
      } catch (ExecutionException e) {
        log.error(STR."Migration step \{version().orElse("unknown")} failed: \{e.getCause().getMessage()}");
        throw e.getCause();
      }
    }
  }
}