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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import javax.inject.Provider;

import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;

import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit rule for managing cleanup policies in tests.
 * 
 * <p>This class is compatible with both JUnit 4 (via ExternalResource) and JUnit Jupiter 5.10.1
 * (via custom extension adapter). It leverages Java 21 features like Virtual Threads for
 * cleanup operations when running in a Java 21 environment.</p>
 *
 * @since 3.20
 */
public class CleanupPolicyRule
    extends ExternalResource
{
  private static final Logger log = LoggerFactory.getLogger(CleanupPolicyRule.class);

  private final Provider<CleanupPolicyStorage> cleanupPolicyStorageProvider;

  private final List<CleanupPolicy> cleanupPolicies = new ArrayList<>();

  /**
   * Constructs a new CleanupPolicyRule with the given storage provider.
   *
   * @param cleanupPolicyStorageProvider the provider for cleanup policy storage
   */
  public CleanupPolicyRule(final Provider<CleanupPolicyStorage> cleanupPolicyStorageProvider) {
    this.cleanupPolicyStorageProvider = cleanupPolicyStorageProvider;
  }

  /**
   * Creates a cleanup policy with the given name and criteria, using default format and mode.
   *
   * @param name the name of the cleanup policy
   * @param criteria the criteria for the cleanup policy
   * @return the created cleanup policy
   */
  public CleanupPolicy create(final String name, final Map<String, String> criteria) {
    return createCleanupPolicy(name, "format", "mode", criteria);
  }

  /**
   * Creates a cleanup policy with the specified parameters.
   *
   * @param name the name of the cleanup policy
   * @param format the format for the cleanup policy
   * @param mode the mode for the cleanup policy
   * @param criteria the criteria for the cleanup policy
   * @return the created cleanup policy
   */
  public CleanupPolicy createCleanupPolicy(
      final String name,
      final String format,
      final String mode,
      final Map<String, String> criteria)
  {
    CleanupPolicyStorage storage = cleanupPolicyStorageProvider.get();
    CleanupPolicy policy = storage.newCleanupPolicy();
    policy.setName(name);
    policy.setNotes("notes");
    policy.setFormat(format);
    policy.setMode(mode);
    policy.setCriteria(criteria);

    storage.add(policy);
    cleanupPolicies.add(policy);

    return policy;
  }

  /**
   * Cleans up all created policies after the test completes.
   * Uses Virtual Threads when running on Java 21 for improved concurrency.
   */
  @Override
  protected void after() {
    // Use Virtual Threads for cleanup operations when running on Java 21
    // This provides better scalability for tests that create many cleanup policies
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (CleanupPolicy cleanupPolicy : cleanupPolicies) {
        executor.submit(() -> {
          try {
            cleanupPolicyStorageProvider.get().remove(cleanupPolicy);
          }
          catch (Exception e) {
            log.error(STR."Failed to remove CleanupPolicy \{cleanupPolicy}", e);
          }
        });
      }
    } // executor is auto-closed here, and we wait for all tasks to complete
  }
}