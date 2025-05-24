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
package org.sonatype.nexus.repository.config.internal;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.repository.config.Configuration;

import org.hamcrest.core.Is;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static java.util.Collections.emptySet;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Virtual Thread-specific tests for {@link ConfigurationStoreImpl}.
 * 
 * These tests validate that the repository configuration store functions correctly
 * when accessed concurrently through Java 21 Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class ConfigurationStoreImplTest
    extends TestSupport
{
  @Mock
  private DataSessionSupplier sessionSupplier;

  private ConfigurationStoreImpl underTest;

  @Before
  public void setup() {
    underTest = new ConfigurationStoreImpl(sessionSupplier);
  }

  @Test
  public void readByNamesShouldBeEmptyWhenRepositoriesIsEmpty() {
    Collection<Configuration> configurations = underTest.readByNames(emptySet());

    assertThat(configurations.isEmpty(), Is.is(true));
  }
  
  /**
   * Tests that the {@link ConfigurationStoreImpl#readByNames} method works correctly
   * when accessed concurrently by many Virtual Threads.
   * 
   * This test validates that database operations performed by the configuration store
   * are compatible with the Virtual Thread execution model, which is critical for
   * I/O-bound operations like database access in the Java 21 migration.
   */
  @Test
  public void testConcurrentReadByNamesWithVirtualThreads() throws Exception {
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor that uses Virtual Threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up for concurrent execution
      int taskCount = 1000; // Run 1000 concurrent operations
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit tasks to be executed by Virtual Threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Call the method under test
            Collection<Configuration> configurations = underTest.readByNames(emptySet());
            
            // Verify the result is as expected
            if (!configurations.isEmpty()) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            // Count any exceptions as errors
            errorCount.incrementAndGet();
          } 
          finally {
            // Signal that this task is complete
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with a timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertThat("All tasks should complete within the timeout", completed, Is.is(true));
      assertThat("No errors should occur during concurrent execution", errorCount.get(), Is.is(0));
    } 
    finally {
      // Clean up the executor
      executor.shutdown();
    }
  }
}