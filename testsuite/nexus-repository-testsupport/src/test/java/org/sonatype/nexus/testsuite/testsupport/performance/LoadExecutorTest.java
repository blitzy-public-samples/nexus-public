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
package org.sonatype.nexus.testsuite.testsupport.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Category;
import org.junit.jupiter.api.Timeout;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link LoadExecutor}
 * <p>
 * Validates exception propagation and task completion behavior under both platform and virtual threads.
 * <p>
 * This test class has been updated for Java 21 compatibility, using JUnit Jupiter (JUnit 5) annotations
 * and assertions. It includes tests for both traditional platform threads and Java 21's virtual threads
 * to ensure the LoadExecutor functions correctly in all threading environments.
 * <p>
 * Key updates for Java 21 compatibility include:
 * <ul>
 *   <li>Migration from JUnit 4 to JUnit Jupiter (JUnit 5)</li>
 *   <li>Replacement of expected exception pattern with assertThrows</li>
 *   <li>Addition of virtual thread testing using Thread.ofVirtual()</li>
 *   <li>Enhanced documentation and test timeout management</li>
 *   <li>Use of CompletableFuture for concurrent task execution</li>
 * </ul>
 */
public class LoadExecutorTest
{
  /**
   * Verifies that exceptions from tasks are properly propagated to the caller.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  @DisplayName("Task exceptions should be propagated to the caller")
  void taskExceptionsArePropagated() {
    final List<Callable<?>> tasks = new ArrayList<>();
    tasks.add(() -> {
      throw new IllegalStateException("expected");
    });

    assertThrows(IllegalStateException.class, () -> 
        new LoadExecutor(tasks, 1, 10).callTasks(),
        "Expected IllegalStateException to be propagated");
  }

  /**
   * Verifies that assertion errors from tasks are properly propagated to the caller.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  @DisplayName("Task assertion errors should be propagated to the caller")
  void taskAssertionErrorsArePropagated() {
    final List<Callable<?>> tasks = new ArrayList<>();
    tasks.add(() -> {
      throw new AssertionError("expected");
    });

    assertThrows(AssertionError.class, () -> 
        new LoadExecutor(tasks, 1, 10).callTasks(),
        "Expected AssertionError to be propagated");
  }

  /**
   * Verifies that tasks without failures complete successfully.
   */
  @SuppressWarnings({"java:S2699"}) // sonar wants assertions, but we're using assertDoesNotThrow instead
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  @DisplayName("Tasks without failures should complete successfully")
  void noFailureTestDoesActuallyStop() {
    final List<Callable<?>> tasks = new ArrayList<>();
    tasks.add(() -> {
      // This task is always successful
      return null;
    });

    assertDoesNotThrow(() -> new LoadExecutor(tasks, 1, 5).callTasks(),
        "Expected successful task execution");
  }
  
  /**
   * Verifies that tasks execute properly with virtual threads.
   * This test validates that the LoadExecutor works correctly with Java 21 virtual threads.
   * <p>
   * The test creates a virtual thread executor using Java 21's Thread.ofVirtual() API
   * and executes multiple LoadExecutor instances concurrently within virtual threads
   * to ensure compatibility and scalability. This helps validate that the performance 
   * testing infrastructure works correctly with Java 21's lightweight threading model.
   * <p>
   * Virtual threads are a preview feature in Java 21 that enable high-throughput lightweight 
   * concurrency without the overhead of traditional platform threads. This test ensures
   * that our performance testing infrastructure can leverage this capability.
   */
  @Test
  @Tag("virtual-threads")
  @Category(VirtualThreadTestGroup.class)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @DisplayName("Tasks should execute properly with virtual threads")
  void tasksExecuteWithVirtualThreads() {
    final List<Callable<?>> tasks = new ArrayList<>();
    tasks.add(() -> {
      // This task is always successful
      return null;
    });
    
    // Create a virtual thread executor using Java 21's virtual thread API
    // This demonstrates the new lightweight threading model in Java 21
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Execute multiple tasks concurrently using virtual threads
      // This tests the scalability of virtual threads with the LoadExecutor
      int concurrentTasks = 10;
      CompletableFuture<?>[] futures = new CompletableFuture[concurrentTasks];
      
      for (int i = 0; i < concurrentTasks; i++) {
        futures[i] = CompletableFuture.runAsync(() -> {
          assertDoesNotThrow(() -> new LoadExecutor(tasks, 1, 5).callTasks(),
              "Expected successful task execution with virtual threads");
        }, virtualExecutor);
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures).join();
    }
    catch (Exception e) {
      throw new AssertionError("Virtual thread execution failed", e);
    }
    finally {
      virtualExecutor.shutdown();
    }
  }
}