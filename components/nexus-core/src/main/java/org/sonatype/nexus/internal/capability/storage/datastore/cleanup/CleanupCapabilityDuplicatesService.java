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
package org.sonatype.nexus.internal.capability.storage.datastore.cleanup;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorage;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Remove all capability duplicate records from storage.
 */
@Named
@Singleton
public class CleanupCapabilityDuplicatesService
    extends ComponentSupport
{
  private final CapabilityStorage capabilityStorage;

  @Inject
  public CleanupCapabilityDuplicatesService(final CapabilityStorage capabilityStorage) {
    this.capabilityStorage = checkNotNull(capabilityStorage);
  }

  /**
   * Cleans up duplicate capability records using Virtual Threads and structured concurrency.
   * This implementation leverages Java 21 features for improved performance with I/O-bound operations.
   */
  public void doCleanup() {
    if (!capabilityStorage.isDuplicatesFound()) {
      log.debug("No capabilities duplicates found.");
      return;
    }

    // Get all capability duplicates
    Map<String, List<String>> duplicatesMap = capabilityStorage.browseCapabilityDuplicates();
    int totalDuplicates = 0;
    
    try (var scope = new ShutdownOnFailure()) {
      // Process each capability type with its duplicates using structured concurrency
      for (var entry : duplicatesMap.entrySet()) {
        String typeId = entry.getKey();
        List<String> duplicates = entry.getValue();
        
        int duplicateCount = duplicates.size() - 1;
        if (duplicateCount > 0) {
          totalDuplicates += duplicateCount;
          log.info("Cleaning up {} duplicates for {} capability", duplicateCount, typeId);
          
          // Get duplicates to remove (skip the first one to keep it)
          // Using enhanced Streams API in Java 21 for more efficient processing
          List<String> duplicatesToRemove = duplicates.stream()
              .skip(1) // left one capability in the storage
              .toList(); // Java 21 enhanced Streams API
          
          // Fork a virtual thread for each duplicate to remove
          for (String identity : duplicatesToRemove) {
            // Each removal operation runs in its own virtual thread for optimal I/O performance
            scope.fork(() -> {
              try {
                if (capabilityStorage.remove(identity)) {
                  log.debug("Capability duplicate {} removed", identity);
                  return true;
                } else {
                  log.warn("Failed to remove capability duplicate {}", identity);
                  return false;
                }
              } catch (Exception e) {
                log.error("Error removing capability duplicate {}: {}", identity, e.getMessage());
                throw e; // Propagate exception to the scope for proper handling
              }
            });
          }
        }
      }
      
      // Wait for all tasks to complete with structured concurrency
      try {
        // Join waits for all subtasks to complete
        scope.join();
        // Throw if any subtask failed
        scope.throwIfFailed(e -> new RuntimeException("Failed to clean up capability duplicates", e));
        log.debug("Successfully cleaned up {} capability duplicates", totalDuplicates);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Cleanup of capability duplicates was interrupted", e);
      } catch (Exception e) {
        log.error("Error during cleanup of capability duplicates", e.getMessage(), e);
      }
    }
  }
}