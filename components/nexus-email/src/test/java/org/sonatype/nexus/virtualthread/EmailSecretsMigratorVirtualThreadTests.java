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
package org.sonatype.nexus.virtualthread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.email.internal.secrets.migration.EmailSecretsMigrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailSecretsMigrator} using Java 21 Virtual Threads to validate
 * concurrent operation and thread safety.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class EmailSecretsMigratorVirtualThreadTests
    extends TestSupport
{
  @Mock
  private EmailManager emailManager;

  @Mock
  private EmailConfiguration emailConfiguration;

  @InjectMocks
  private EmailSecretsMigrator underTest;

  /**
   * Test that verifies the basic migration functionality works as expected.
   */
  @Test
  public void testBasicMigration() {
    Secret notMigrated = getMockSecret("legacy", "legacyPassword");
    when(emailConfiguration.getPassword()).thenReturn(notMigrated);
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);

    underTest.migrate();
    verify(emailManager).setConfiguration(emailConfiguration, "legacyPassword");
  }

  /**
   * Test that verifies the migration is skipped when not needed.
   */
  @Test
  public void testSkipMigration() {
    Secret alreadyMigrated = getMockSecret("_1", "alreadyMigrated");
    when(emailConfiguration.getPassword()).thenReturn(alreadyMigrated);
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);

    underTest.migrate();
    verify(emailManager, never()).setConfiguration(any(EmailConfiguration.class), anyString());

    when(emailConfiguration.getPassword()).thenReturn(null);
    underTest.migrate();
    verify(emailManager, never()).setConfiguration(any(EmailConfiguration.class), anyString());
  }

  /**
   * Test that verifies the migration works correctly when executed concurrently by multiple virtual threads.
   * This test creates a large number of virtual threads that all attempt to migrate at the same time,
   * validating that the migration process is thread-safe and handles concurrency correctly.
   */
  @Test
  public void testConcurrentMigration() throws Exception {
    // Setup the mock to return a legacy secret that needs migration
    Secret notMigrated = getMockSecret("legacy", "legacyPassword");
    when(emailConfiguration.getPassword()).thenReturn(notMigrated);
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);

    // Number of concurrent threads to run
    int threadCount = 100;
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor that creates a new virtual thread for each task
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Use CountDownLatch to coordinate thread completion
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Track any errors that occur during execution
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent migration tasks
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            underTest.migrate();
          } catch (Exception e) {
            log.error("Error during migration", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout for safety)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertEquals(true, completed, "Not all threads completed within the timeout period");
      assertEquals(0, errorCount.get(), "Some threads encountered errors during migration");
      
      // Verify that setConfiguration was called exactly once, regardless of how many threads attempted migration
      // This verifies that the migration logic properly handles concurrent access
      verify(emailManager, times(1)).setConfiguration(emailConfiguration, "legacyPassword");
      
    } finally {
      // Ensure executor is shut down properly
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Helper method to create a mock Secret with the specified ID and token.
   */
  private Secret getMockSecret(String tokenId, String token) {
    Secret secret = mock(Secret.class);
    when(secret.getId()).thenReturn(tokenId);
    when(secret.decrypt()).thenReturn(token.toCharArray());
    return secret;
  }
}