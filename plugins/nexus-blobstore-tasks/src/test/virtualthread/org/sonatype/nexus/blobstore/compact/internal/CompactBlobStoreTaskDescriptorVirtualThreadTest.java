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
package org.sonatype.nexus.blobstore.compact.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.sonatype.nexus.scheduling.TaskDescriptorSupport.MULTINODE_KEY;

/**
 * Tests for {@link CompactBlobStoreTaskDescriptor} with Java 21 Virtual Threads.
 * 
 * This test ensures that the task descriptor properly configures tasks to work with
 * Virtual Threads and doesn't introduce thread pinning issues.
 */
@ExtendWith(MockitoExtension.class)
class CompactBlobStoreTaskDescriptorVirtualThreadTest
    extends TestSupport
{
  private CompactBlobStoreTaskDescriptor underTest;

  private TaskConfiguration taskConfiguration;

  @BeforeEach
  void setUp() {
    underTest = new CompactBlobStoreTaskDescriptor();
    taskConfiguration = new TaskConfiguration();
  }

  /**
   * Verifies that the task descriptor initializes configuration correctly.
   * This is a basic test migrated from the original JUnit 4 test.
   */
  @Test
  void initializeConfiguration() {
    underTest.initializeConfiguration(taskConfiguration);
    assertThat(taskConfiguration.getBoolean(MULTINODE_KEY, false), is(false));
  }

  /**
   * Verifies that the task descriptor can be used with Virtual Threads.
   * This test creates multiple Virtual Threads and ensures they can all
   * initialize task configurations without issues.
   */
  @Test
  void initializeConfigurationWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000; // Run a large number of concurrent tasks
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        TaskConfiguration config = new TaskConfiguration();
        executor.submit(() -> {
          try {
            // Initialize the configuration in a virtual thread
            underTest.initializeConfiguration(config);
            
            // Verify the configuration was initialized correctly
            if (!config.getBoolean(MULTINODE_KEY, true)) {
              // Configuration is correct
            } else {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertThat("All virtual threads should complete in time", 
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Verify no errors occurred
      assertThat("No errors should occur when using virtual threads", 
          errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that the task descriptor doesn't cause thread pinning issues.
   * Thread pinning occurs when a virtual thread is forced to stay on its carrier thread,
   * which negates the benefits of virtual threads.
   */
  @Test
  void doesNotCauseThreadPinning() {
    // Use the VirtualThreadExecutor to detect thread pinning
    assertDoesNotThrow(() -> {
      VirtualThreadExecutor.execute(() -> {
        // Initialize the configuration in a virtual thread
        TaskConfiguration config = new TaskConfiguration();
        underTest.initializeConfiguration(config);
        
        // Verify the configuration was initialized correctly
        assertThat(config.getBoolean(MULTINODE_KEY, true), is(false));
      });
    });
  }
}