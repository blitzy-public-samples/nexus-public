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
package org.sonatype.nexus.testsuite.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link VirtualThreadExecutor}
 * 
 * This test class validates the VirtualThreadExecutor utility which provides a framework for executing tasks
 * using Java 21's Virtual Threads. It ensures that exceptions from tasks are properly propagated,
 * assertion errors are correctly handled, and successful task execution completes normally.
 */
public class VirtualThreadExecutorTest
{
  /**
   * Tests that exceptions from tasks executed with Virtual Threads are properly propagated.
   */
  @Test(expected = IllegalStateException.class)
  public void taskExceptionsArePropagated() throws Exception {
    final List<Callable<?>> tasks = new ArrayList<>();
    tasks.add(new Callable<Void>()
    {
      @Override
      public Void call() throws Exception {
        throw new IllegalStateException("expected");
      }
    });

    new VirtualThreadExecutor(tasks).executeTasks();
  }

  /**
   * Tests that assertion errors from tasks executed with Virtual Threads are properly propagated.
   */
  @Test(expected = AssertionError.class)
  public void taskAssertionErrorsArePropagated() throws Exception {
    final List<Callable<?>> tasks = new ArrayList<>();
    tasks.add(new Callable<Void>()
    {
      @Override
      public Void call() throws Exception {
        throw new AssertionError("expected");
      }
    });

    new VirtualThreadExecutor(tasks).executeTasks();
  }

  /**
   * Tests that successful task execution with Virtual Threads completes normally.
   */
  @Test
  public void successfulTasksCompleteNormally() throws Exception {
    final List<String> results = new ArrayList<>();
    final List<Callable<String>> tasks = new ArrayList<>();
    tasks.add(new Callable<String>()
    {
      @Override
      public String call() throws Exception {
        return "success";
      }
    });

    List<String> taskResults = new VirtualThreadExecutor(tasks).executeTasks();
    assertEquals(1, taskResults.size());
    assertEquals("success", taskResults.get(0));
  }

  /**
   * Tests that the executor can handle a high number of concurrent Virtual Threads.
   */
  @Test
  public void highConcurrencyTest() throws Exception {
    final int taskCount = 1000; // High number of tasks to test concurrency
    final List<Callable<Integer>> tasks = new ArrayList<>();
    
    for (int i = 0; i < taskCount; i++) {
      final int taskNumber = i;
      tasks.add(new Callable<Integer>() {
        @Override
        public Integer call() throws Exception {
          // Simulate some work
          Thread.sleep(10);
          return taskNumber;
        }
      });
    }

    List<Integer> results = new VirtualThreadExecutor(tasks).executeTasks();
    assertEquals(taskCount, results.size());
    
    // Verify all tasks completed successfully
    boolean[] taskCompleted = new boolean[taskCount];
    for (Integer result : results) {
      taskCompleted[result] = true;
    }
    
    for (int i = 0; i < taskCount; i++) {
      assertTrue("Task " + i + " did not complete", taskCompleted[i]);
    }
  }

  /**
   * Tests direct usage of Java 21's Virtual Threads via ExecutorService.
   */
  @Test
  public void directVirtualThreadExecutorTest() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> future = executor.submit(() -> {
        // Verify this is running in a virtual thread
        assertTrue("Task not running in a virtual thread", Thread.currentThread().isVirtual());
        return "executed in virtual thread";
      });
      
      String result = future.get(5, TimeUnit.SECONDS);
      assertEquals("executed in virtual thread", result);
    }
  }

  /**
   * Tests that exceptions from tasks executed directly with Java 21's Virtual Threads are properly propagated.
   */
  @Test
  public void directVirtualThreadExceptionTest() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> future = executor.submit(() -> {
        throw new RuntimeException("Virtual thread exception");
      });
      
      try {
        future.get(5, TimeUnit.SECONDS);
        fail("Expected exception was not thrown");
      } catch (ExecutionException e) {
        assertTrue(e.getCause() instanceof RuntimeException);
        assertEquals("Virtual thread exception", e.getCause().getMessage());
      }
    }
  }
}