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
package org.sonatype.nexus.testsuite.testsupport.system;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;
import org.sonatype.nexus.common.event.EventManager;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Test support system for cleanup policy management.
 * <p>
 * This class provides utilities for creating, retrieving, and managing cleanup policies during tests.
 * It automatically cleans up any policies created through it when the test completes.
 * </p>
 * <p>
 * This implementation has been optimized for Java 21 with virtual threads to improve performance
 * when cleaning up policies during test teardown, especially for I/O-bound operations.
 * </p>
 * 
 * @since 3.0
 */
@Named
@Singleton
public class CleanupTestSystem
    extends TestSystemSupport
{
  private final CleanupPolicyStorage cleanupPolicyStorage;

  private final Set<String> names = new HashSet<>();

  @Inject
  public CleanupTestSystem(final CleanupPolicyStorage cleanupPolicyStorage, final EventManager eventManager) {
    super(eventManager);
    this.cleanupPolicyStorage = checkNotNull(cleanupPolicyStorage);
  }

  /**
   * Retrieves a cleanup policy by name.
   *
   * @param name the name of the cleanup policy to retrieve
   * @return the cleanup policy, or null if not found
   */
  @Nullable
  public CleanupPolicy get(final String name) {
    return cleanupPolicyStorage.get(name);
  }

  /**
   * Creates a cleanup policy with the specified name and notes, using ALL_FORMATS as the format.
   *
   * @param name the name of the cleanup policy
   * @param notes the notes for the cleanup policy
   * @return the created cleanup policy
   */
  public CleanupPolicy createCleanupPolicy(final String name, final String notes) {
    return createCleanupPolicy(name, "ALL_FORMATS", notes, Collections.emptyMap());
  }

  /**
   * Creates a cleanup policy with the specified parameters.
   *
   * @param name the name of the cleanup policy
   * @param format the format for the cleanup policy
   * @param notes the notes for the cleanup policy
   * @param criteria the criteria for the cleanup policy
   * @return the created cleanup policy
   */
  public CleanupPolicy createCleanupPolicy(
      final String name,
      final String format,
      final String notes,
      final Map<String, String> criteria)
  {
    CleanupPolicy policy = cleanupPolicyStorage.newCleanupPolicy();
    policy.setName(name);
    policy.setNotes(notes);
    policy.setFormat(format);
    policy.setMode("delete");
    policy.setCriteria(criteria);

    managePolicy(name);

    return cleanupPolicyStorage.add(policy);
  }

  /**
   * Adds a policy name to the managed set for cleanup after test completion.
   *
   * @param name the name of the policy to manage
   */
  public void managePolicy(final String name) {
    names.add(name);
  }

  /**
   * Cleans up all managed policies using virtual threads for improved performance.
   * <p>
   * This method leverages Java 21 virtual threads to efficiently handle I/O-bound operations
   * when removing cleanup policies, reducing resource usage and improving concurrency.
   * </p>
   */
  @Override
  protected void doAfter() {
    // Use virtual threads for efficient I/O operations when removing policies
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit policy removal tasks to the virtual thread executor
      var futures = names.stream()
          .map(name -> executor.submit(() -> {
            CleanupPolicy policy = cleanupPolicyStorage.get(name);
            if (policy != null) {
              cleanupPolicyStorage.remove(policy);
            }
            return null;
          }))
          .toList();
      
      // Wait for all removal tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (Exception e) {
          // Log and continue with other removals
          Thread.currentThread().interrupt();
          throw new RuntimeException(STR."Failed to remove cleanup policy: \{e.getMessage()}", e);
        }
      }
    }
  }
}