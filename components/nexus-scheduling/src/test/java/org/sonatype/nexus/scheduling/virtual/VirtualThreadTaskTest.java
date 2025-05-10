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
package org.sonatype.nexus.scheduling.virtual;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for Virtual Thread support in tasks.
 * These tests will be skipped if running on Java versions prior to 21.
 *
 * @since 3.60
 */
public class VirtualThreadTaskTest
    extends TestSupport
{
  private static final String TEST_USER = "test-user";
  private static final String TEST_MDC_KEY = "test-key";
  private static final String TEST_MDC_VALUE = "test-value";

  @Mock
  private Subject subject;

  private boolean isJava21OrLater;

  @Before
  public void setUp() throws Exception {
    // Check if we're running on Java 21 or later
    try {
      // Try to access the isVirtual method which only exists in Java 21+
      Thread.class.getMethod("isVirtual");
      isJava21OrLater = true;
    } catch (NoSuchMethodException e) {
      isJava21OrLater = false;
    }
    
    // Setup Shiro security context for testing
    when(subject.getPrincipal()).thenReturn(TEST_USER);
    ThreadContext.bind(subject);
    
    // Setup MDC context
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
  }

  @After
  public void tearDown() {
    // Clean up Shiro security context and MDC
    ThreadContext.unbindSubject();
    MDC.clear();
  }

  @Test
  public void testSecurityContextPropagationWithVirtualThreads() throws Exception {
    // Skip test if not running on Java 21+
    Assume.assumeTrue("This test requires Java 21 or later", isJava21OrLater);
    
    // Create a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    NexusExecutorService executor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
    
    // Use a latch to wait for the task to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Values captured from the virtual thread
    final Object[] captured = new Object[2];
    
    // Submit a task that will run on a virtual thread
    executor.submit(() -> {
      try {
        // Capture the security context and MDC in the virtual thread
        captured[0] = SecurityUtils.getSubject().getPrincipal();
        captured[1] = MDC.get(TEST_MDC_KEY);
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the task to complete
    assertThat("Task did not complete in time", latch.await(5, TimeUnit.SECONDS), is(true));
    
    // Verify security context was propagated
    assertThat("Security context was not propagated", captured[0], is(TEST_USER));
    
    // Verify MDC was propagated
    assertThat("MDC was not propagated", captured[1], is(TEST_MDC_VALUE));
    
    // Shutdown the executor
    executor.shutdown();
  }

  @Test
  public void testMDCCleanupAfterVirtualThreadExecution() throws Exception {
    // Skip test if not running on Java 21+
    Assume.assumeTrue("This test requires Java 21 or later", isJava21OrLater);
    
    // Create a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    NexusExecutorService executor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
    
    // Use a latch to wait for the task to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Submit a task that will run on a virtual thread and modify MDC
    executor.submit(() -> {
      try {
        // Modify MDC in the virtual thread
        MDC.put("virtual-thread-key", "virtual-thread-value");
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the task to complete
    assertThat("Task did not complete in time", latch.await(5, TimeUnit.SECONDS), is(true));
    
    // Submit another task to verify MDC is clean
    CountDownLatch latch2 = new CountDownLatch(1);
    final Object[] captured = new Object[1];
    
    executor.submit(() -> {
      try {
        // Capture the MDC in the new virtual thread
        captured[0] = MDC.get("virtual-thread-key");
      } finally {
        latch2.countDown();
      }
    });
    
    // Wait for the second task to complete
    assertThat("Second task did not complete in time", latch2.await(5, TimeUnit.SECONDS), is(true));
    
    // Verify MDC was cleaned up between tasks
    assertThat("MDC was not cleaned up between tasks", captured[0], nullValue());
    
    // Shutdown the executor
    executor.shutdown();
  }

  @Test
  public void testVirtualThreadDetection() throws Exception {
    // Skip test if not running on Java 21+
    Assume.assumeTrue("This test requires Java 21 or later", isJava21OrLater);
    
    // Create a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    NexusExecutorService executor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
    
    // Use a latch to wait for the task to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Values captured from the virtual thread
    final Boolean[] isVirtual = new Boolean[1];
    
    // Submit a task that will run on a virtual thread
    executor.submit(() -> {
      try {
        // Check if this is a virtual thread using reflection
        // (to maintain compatibility with Java 17)
        try {
          isVirtual[0] = (Boolean) Thread.currentThread().getClass()
              .getMethod("isVirtual")
              .invoke(Thread.currentThread());
        } catch (Exception e) {
          isVirtual[0] = false;
        }
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the task to complete
    assertThat("Task did not complete in time", latch.await(5, TimeUnit.SECONDS), is(true));
    
    // Verify the thread was detected as virtual
    assertThat("Thread was not detected as virtual", isVirtual[0], is(true));
    
    // Shutdown the executor
    executor.shutdown();
  }
}