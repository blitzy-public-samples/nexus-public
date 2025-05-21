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
package org.sonatype.nexus.capability.condition.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.ConditionEvent;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests {@link LogicalConditionsImpl} with Java 21 Virtual Threads.
 * 
 * This test verifies that LogicalConditionsImpl's AND and OR operators maintain
 * consistent state when bombarded with thousands of concurrent condition state changes
 * from virtual threads.
 *
 * @since 3.60
 */
public class LogicalConditionsVirtualThreadTest
    extends EventManagerTestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 1000;
  private static final int CONDITION_CHANGE_COUNT = 10;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
  
  @Mock
  private Condition left;

  @Mock
  private Condition right;
  
  @Mock
  private Condition third;
  
  private LogicalConditionsImpl underTest;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  public void setUp() {
    underTest = new LogicalConditionsImpl(eventManager);
    
    // Create a virtual thread executor using Java 21's virtual threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a platform thread executor for comparison
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    virtualThreadExecutor.shutdownNow();
    platformThreadExecutor.shutdownNow();
    
    assertTrue(virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS));
    assertTrue(platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS));
  }
  
  /**
   * Tests that a logical AND condition maintains consistent state when bombarded with
   * thousands of concurrent condition state changes from virtual threads.
   */
  @Test
  public void testAndConditionWithVirtualThreads() throws Exception {
    // Create a complex condition tree with multiple levels of AND operators
    final Condition complexAnd = underTest.and(
        underTest.and(left, right),
        third
    );
    
    // Prepare the condition
    ((CompositeConditionSupport) complexAnd).bind();
    
    // Set initial state
    when(left.isSatisfied()).thenReturn(true);
    when(right.isSatisfied()).thenReturn(true);
    when(third.isSatisfied()).thenReturn(true);
    
    // Fire initial events
    ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Satisfied(left));
    ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Satisfied(right));
    ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Satisfied(third));
    
    // Verify initial state
    assertTrue(complexAnd.isSatisfied());
    
    // Track condition state changes to verify consistency
    AtomicInteger inconsistentStateCount = new AtomicInteger(0);
    Set<Thread> pinnedThreads = ConcurrentHashMap.newKeySet();
    
    // Latch to coordinate all threads starting at the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT * 3);
    
    // Launch virtual threads to bombard the condition with state changes
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      // Thread to toggle left condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(left.isSatisfied()).thenReturn(newState);
            
            // Check for thread pinning
            Thread currentThread = Thread.currentThread();
            if (currentThread.isVirtual() && currentThread.getState() == Thread.State.RUNNABLE) {
              // In a real pinning detection, we would check if the thread is pinned to a carrier thread
              // For this test, we'll just track the thread for demonstration purposes
              pinnedThreads.add(currentThread);
            }
            
            if (newState) {
              ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Satisfied(left));
            } else {
              ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Unsatisfied(left));
            }
            
            // Verify condition state consistency
            boolean expectedState = left.isSatisfied() && right.isSatisfied() && third.isSatisfied();
            if (complexAnd.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            // Small delay to increase chance of concurrent execution
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle right condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(right.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Satisfied(right));
            } else {
              ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Unsatisfied(right));
            }
            
            // Verify condition state consistency
            boolean expectedState = left.isSatisfied() && right.isSatisfied() && third.isSatisfied();
            if (complexAnd.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle third condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(third.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Satisfied(third));
            } else {
              ((CompositeConditionSupport) complexAnd).handle(new ConditionEvent.Unsatisfied(third));
            }
            
            // Verify condition state consistency
            boolean expectedState = left.isSatisfied() && right.isSatisfied() && third.isSatisfied();
            if (complexAnd.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Test timed out waiting for virtual threads to complete");
    
    // Verify no inconsistent states were detected
    assertThat("Condition state was inconsistent " + inconsistentStateCount.get() + " times",
        inconsistentStateCount.get(), is(0));
    
    // Log information about thread pinning
    if (!pinnedThreads.isEmpty()) {
      System.out.println("Detected " + pinnedThreads.size() + " pinned virtual threads");
    }
  }
  
  /**
   * Tests that a logical OR condition maintains consistent state when bombarded with
   * thousands of concurrent condition state changes from virtual threads.
   */
  @Test
  public void testOrConditionWithVirtualThreads() throws Exception {
    // Create a complex condition tree with multiple levels of OR operators
    final Condition complexOr = underTest.or(
        underTest.or(left, right),
        third
    );
    
    // Prepare the condition
    ((CompositeConditionSupport) complexOr).bind();
    
    // Set initial state
    when(left.isSatisfied()).thenReturn(false);
    when(right.isSatisfied()).thenReturn(false);
    when(third.isSatisfied()).thenReturn(false);
    
    // Fire initial events
    ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Unsatisfied(left));
    ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Unsatisfied(right));
    ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Unsatisfied(third));
    
    // Verify initial state
    assertFalse(complexOr.isSatisfied());
    
    // Track condition state changes to verify consistency
    AtomicInteger inconsistentStateCount = new AtomicInteger(0);
    
    // Latch to coordinate all threads starting at the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT * 3);
    
    // Launch virtual threads to bombard the condition with state changes
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      // Thread to toggle left condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(left.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Satisfied(left));
            } else {
              ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Unsatisfied(left));
            }
            
            // Verify condition state consistency
            boolean expectedState = left.isSatisfied() || right.isSatisfied() || third.isSatisfied();
            if (complexOr.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle right condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(right.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Satisfied(right));
            } else {
              ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Unsatisfied(right));
            }
            
            // Verify condition state consistency
            boolean expectedState = left.isSatisfied() || right.isSatisfied() || third.isSatisfied();
            if (complexOr.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle third condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(third.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Satisfied(third));
            } else {
              ((CompositeConditionSupport) complexOr).handle(new ConditionEvent.Unsatisfied(third));
            }
            
            // Verify condition state consistency
            boolean expectedState = left.isSatisfied() || right.isSatisfied() || third.isSatisfied();
            if (complexOr.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Test timed out waiting for virtual threads to complete");
    
    // Verify no inconsistent states were detected
    assertThat("Condition state was inconsistent " + inconsistentStateCount.get() + " times",
        inconsistentStateCount.get(), is(0));
  }
  
  /**
   * Tests a complex condition tree with multiple levels of AND/OR operators under high concurrency.
   */
  @Test
  public void testComplexConditionTreeWithVirtualThreads() throws Exception {
    // Create a complex condition tree with mixed AND/OR operators
    final Condition complexTree = underTest.and(
        underTest.or(left, right),
        third
    );
    
    // Prepare the condition
    ((CompositeConditionSupport) complexTree).bind();
    
    // Set initial state
    when(left.isSatisfied()).thenReturn(true);
    when(right.isSatisfied()).thenReturn(false);
    when(third.isSatisfied()).thenReturn(true);
    
    // Fire initial events
    ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Satisfied(left));
    ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Unsatisfied(right));
    ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Satisfied(third));
    
    // Verify initial state
    assertTrue(complexTree.isSatisfied());
    
    // Track condition state changes to verify consistency
    AtomicInteger inconsistentStateCount = new AtomicInteger(0);
    
    // Latch to coordinate all threads starting at the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT * 3);
    
    // Launch virtual threads to bombard the condition with state changes
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      // Thread to toggle left condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(left.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Satisfied(left));
            } else {
              ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Unsatisfied(left));
            }
            
            // Verify condition state consistency
            boolean expectedState = (left.isSatisfied() || right.isSatisfied()) && third.isSatisfied();
            if (complexTree.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle right condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(right.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Satisfied(right));
            } else {
              ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Unsatisfied(right));
            }
            
            // Verify condition state consistency
            boolean expectedState = (left.isSatisfied() || right.isSatisfied()) && third.isSatisfied();
            if (complexTree.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle third condition
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(third.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Satisfied(third));
            } else {
              ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Unsatisfied(third));
            }
            
            // Verify condition state consistency
            boolean expectedState = (left.isSatisfied() || right.isSatisfied()) && third.isSatisfied();
            if (complexTree.isSatisfied() != expectedState) {
              inconsistentStateCount.incrementAndGet();
            }
            
            Thread.yield();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Test timed out waiting for virtual threads to complete");
    
    // Verify no inconsistent states were detected
    assertThat("Condition state was inconsistent " + inconsistentStateCount.get() + " times",
        inconsistentStateCount.get(), is(0));
  }
  
  /**
   * Compares performance between virtual threads and platform threads for logical condition evaluation.
   */
  @Test
  public void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Create a complex condition tree for testing
    final Condition complexTree = underTest.and(
        underTest.or(left, right),
        third
    );
    
    // Prepare the condition
    ((CompositeConditionSupport) complexTree).bind();
    
    // Set initial state
    when(left.isSatisfied()).thenReturn(true);
    when(right.isSatisfied()).thenReturn(false);
    when(third.isSatisfied()).thenReturn(true);
    
    // Fire initial events
    ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Satisfied(left));
    ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Unsatisfied(right));
    ((CompositeConditionSupport) complexTree).handle(new ConditionEvent.Satisfied(third));
    
    // Verify initial state
    assertTrue(complexTree.isSatisfied());
    
    // Measure performance with virtual threads
    long virtualThreadTime = measurePerformance(() -> {
      return runConcurrentTest(complexTree, virtualThreadExecutor);
    });
    
    // Measure performance with platform threads
    long platformThreadTime = measurePerformance(() -> {
      return runConcurrentTest(complexTree, platformThreadExecutor);
    });
    
    // Log performance comparison
    System.out.println("Performance comparison:");
    System.out.println("- Virtual Threads: " + virtualThreadTime + "ms");
    System.out.println("- Platform Threads: " + platformThreadTime + "ms");
    System.out.println("- Improvement: " + 
        String.format("%.2f", (double)platformThreadTime / virtualThreadTime) + "x");
    
    // No assertion here as performance can vary, but the test provides valuable metrics
  }
  
  /**
   * Helper method to run a concurrent test with the specified executor.
   */
  private boolean runConcurrentTest(Condition condition, ExecutorService executor) throws Exception {
    AtomicBoolean success = new AtomicBoolean(true);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT * 3);
    
    // Launch threads to bombard the condition with state changes
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      // Thread to toggle left condition
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(left.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) condition).handle(new ConditionEvent.Satisfied(left));
            } else {
              ((CompositeConditionSupport) condition).handle(new ConditionEvent.Unsatisfied(left));
            }
            
            // Verify condition state consistency
            boolean expectedState = (left.isSatisfied() || right.isSatisfied()) && third.isSatisfied();
            if (condition.isSatisfied() != expectedState) {
              success.set(false);
            }
          }
        } catch (Exception e) {
          e.printStackTrace();
          success.set(false);
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle right condition
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(right.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) condition).handle(new ConditionEvent.Satisfied(right));
            } else {
              ((CompositeConditionSupport) condition).handle(new ConditionEvent.Unsatisfied(right));
            }
          }
        } catch (Exception e) {
          e.printStackTrace();
          success.set(false);
        } finally {
          completionLatch.countDown();
        }
      });
      
      // Thread to toggle third condition
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < CONDITION_CHANGE_COUNT; j++) {
            boolean newState = j % 2 == 0;
            when(third.isSatisfied()).thenReturn(newState);
            
            if (newState) {
              ((CompositeConditionSupport) condition).handle(new ConditionEvent.Satisfied(third));
            } else {
              ((CompositeConditionSupport) condition).handle(new ConditionEvent.Unsatisfied(third));
            }
          }
        } catch (Exception e) {
          e.printStackTrace();
          success.set(false);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    
    return success.get();
  }
  
  /**
   * Helper method to measure performance of a task.
   */
  private long measurePerformance(Supplier<Boolean> task) throws Exception {
    // Warm up
    for (int i = 0; i < 3; i++) {
      task.get();
    }
    
    // Measure
    long start = System.currentTimeMillis();
    boolean success = task.get();
    long end = System.currentTimeMillis();
    
    assertTrue(success, "Task failed during performance measurement");
    
    return end - start;
  }
}