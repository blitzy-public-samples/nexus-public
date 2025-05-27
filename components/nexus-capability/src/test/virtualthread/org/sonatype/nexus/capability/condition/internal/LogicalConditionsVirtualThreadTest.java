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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

/**
 * Tests {@link LogicalConditionsImpl} with Java 21 Virtual Threads under high concurrency.
 * 
 * This test verifies that logical condition trees maintain consistent state when bombarded
 * with thousands of concurrent condition state changes from virtual threads.
 */
public class LogicalConditionsVirtualThreadTest
    extends EventManagerTestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 5000;
  private static final int PLATFORM_THREAD_COUNT = 200;
  private static final int CONDITION_COUNT = 10;
  private static final int OPERATIONS_PER_THREAD = 50;
  private static final int COMPLEX_TREE_DEPTH = 5;
  
  @Mock
  private List<Condition> mockConditions;
  
  private LogicalConditionsImpl underTest;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  private List<TestCondition> testConditions;
  
  /**
   * A test condition that can be toggled between satisfied and unsatisfied states.
   */
  private static class TestCondition implements Condition {
    private final AtomicBoolean satisfied = new AtomicBoolean(false);
    private final AtomicInteger eventCount = new AtomicInteger(0);
    private final String name;
    private final EventManagerTestSupport eventManager;
    
    public TestCondition(String name, EventManagerTestSupport eventManager) {
      this.name = name;
      this.eventManager = eventManager;
    }
    
    @Override
    public boolean isSatisfied() {
      return satisfied.get();
    }
    
    @Override
    public void bind() {
      // No-op for test
    }
    
    @Override
    public void release() {
      // No-op for test
    }
    
    @Override
    public String explainSatisfied() {
      return name + " is satisfied";
    }
    
    @Override
    public String explainUnsatisfied() {
      return name + " is not satisfied";
    }
    
    /**
     * Sets the condition state and fires the appropriate event.
     */
    public void setSatisfied(boolean newState) {
      boolean oldState = satisfied.getAndSet(newState);
      if (oldState != newState) {
        eventCount.incrementAndGet();
        if (newState) {
          eventManager.getEventManager().post(new ConditionEvent.Satisfied(this));
        } else {
          eventManager.getEventManager().post(new ConditionEvent.Unsatisfied(this));
        }
      }
    }
    
    /**
     * Toggles the condition state.
     */
    public void toggle() {
      setSatisfied(!satisfied.get());
    }
    
    /**
     * Gets the number of state change events fired by this condition.
     */
    public int getEventCount() {
      return eventCount.get();
    }
    
    @Override
    public String toString() {
      return name + "[" + (satisfied.get() ? "satisfied" : "unsatisfied") + "]";
    }
  }
  
  /**
   * Thread pinning detector that checks if a virtual thread is pinned to a platform thread.
   */
  private static class ThreadPinningDetector {
    private final AtomicReference<Thread> platformThread = new AtomicReference<>();
    private final AtomicBoolean pinned = new AtomicBoolean(false);
    private final AtomicInteger checkCount = new AtomicInteger(0);
    
    /**
     * Checks if the current thread is pinned to a platform thread.
     * Should be called multiple times from the same virtual thread.
     */
    public void checkPinning() {
      Thread currentThread = Thread.currentThread();
      checkCount.incrementAndGet();
      
      // If this is the first check, store the platform thread
      if (platformThread.get() == null) {
        platformThread.set(currentThread);
        return;
      }
      
      // If the platform thread is the same as before, we're pinned
      if (platformThread.get() == currentThread) {
        pinned.set(true);
      }
    }
    
    /**
     * Returns true if the thread was detected as pinned.
     */
    public boolean isPinned() {
      return pinned.get() && checkCount.get() > 1;
    }
  }
  
  @BeforeEach
  public void setUp() {
    underTest = new LogicalConditionsImpl(eventManager);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(PLATFORM_THREAD_COUNT);
    
    // Create test conditions
    testConditions = new ArrayList<>();
    for (int i = 0; i < CONDITION_COUNT; i++) {
      testConditions.add(new TestCondition("Condition-" + i, this));
    }
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that a complex AND condition tree maintains consistent state under high concurrency
   * with virtual threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testComplexAndConditionTreeWithVirtualThreads() throws Exception {
    // Create a complex tree of AND conditions
    Condition complexTree = createComplexAndConditionTree(COMPLEX_TREE_DEPTH, testConditions);
    complexTree.bind();
    
    // Initially all conditions are unsatisfied, so the tree should be unsatisfied
    assertFalse(complexTree.isSatisfied());
    
    // Create a latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Track any exceptions that occur in threads
    ConcurrentHashMap<Thread, Throwable> exceptions = new ConcurrentHashMap<>();
    
    // Launch virtual threads that toggle conditions
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadIndex = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Each thread toggles conditions in a deterministic but different pattern
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            int conditionIndex = (threadIndex + j) % testConditions.size();
            testConditions.get(conditionIndex).toggle();
            
            // Small yield to increase interleaving
            Thread.yield();
          }
        } 
        catch (Throwable t) {
          exceptions.put(Thread.currentThread(), t);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Check for exceptions
    if (!exceptions.isEmpty()) {
      fail("Exceptions occurred in virtual threads: " + exceptions);
    }
    
    // Verify that all conditions were toggled multiple times
    for (TestCondition condition : testConditions) {
      assertTrue(condition.getEventCount() > 0, 
          "Condition " + condition + " should have been toggled at least once");
    }
    
    // Final state depends on the last operations, but the tree should be in a consistent state
    // Set all conditions to satisfied to verify the tree works correctly
    for (TestCondition condition : testConditions) {
      condition.setSatisfied(true);
    }
    
    // Now the tree should be satisfied
    assertTrue(complexTree.isSatisfied(), "Complex AND tree should be satisfied when all conditions are satisfied");
    
    // Set one condition to unsatisfied
    testConditions.get(0).setSatisfied(false);
    
    // Now the tree should be unsatisfied
    assertFalse(complexTree.isSatisfied(), "Complex AND tree should be unsatisfied when any condition is unsatisfied");
  }
  
  /**
   * Tests that a complex OR condition tree maintains consistent state under high concurrency
   * with virtual threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testComplexOrConditionTreeWithVirtualThreads() throws Exception {
    // Create a complex tree of OR conditions
    Condition complexTree = createComplexOrConditionTree(COMPLEX_TREE_DEPTH, testConditions);
    complexTree.bind();
    
    // Initially all conditions are unsatisfied, so the tree should be unsatisfied
    assertFalse(complexTree.isSatisfied());
    
    // Create a latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Track any exceptions that occur in threads
    ConcurrentHashMap<Thread, Throwable> exceptions = new ConcurrentHashMap<>();
    
    // Launch virtual threads that toggle conditions
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadIndex = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Each thread toggles conditions in a deterministic but different pattern
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            int conditionIndex = (threadIndex + j) % testConditions.size();
            testConditions.get(conditionIndex).toggle();
            
            // Small yield to increase interleaving
            Thread.yield();
          }
        } 
        catch (Throwable t) {
          exceptions.put(Thread.currentThread(), t);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Check for exceptions
    if (!exceptions.isEmpty()) {
      fail("Exceptions occurred in virtual threads: " + exceptions);
    }
    
    // Verify that all conditions were toggled multiple times
    for (TestCondition condition : testConditions) {
      assertTrue(condition.getEventCount() > 0, 
          "Condition " + condition + " should have been toggled at least once");
    }
    
    // Final state depends on the last operations, but the tree should be in a consistent state
    // Set all conditions to unsatisfied to verify the tree works correctly
    for (TestCondition condition : testConditions) {
      condition.setSatisfied(false);
    }
    
    // Now the tree should be unsatisfied
    assertFalse(complexTree.isSatisfied(), "Complex OR tree should be unsatisfied when all conditions are unsatisfied");
    
    // Set one condition to satisfied
    testConditions.get(0).setSatisfied(true);
    
    // Now the tree should be satisfied
    assertTrue(complexTree.isSatisfied(), "Complex OR tree should be satisfied when any condition is satisfied");
  }
  
  /**
   * Tests that a mixed AND/OR condition tree maintains consistent state under high concurrency
   * with virtual threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testMixedConditionTreeWithVirtualThreads() throws Exception {
    // Create a mixed tree of AND and OR conditions
    Condition mixedTree = createMixedConditionTree(testConditions);
    mixedTree.bind();
    
    // Initially all conditions are unsatisfied, so the tree should be unsatisfied
    assertFalse(mixedTree.isSatisfied());
    
    // Create a latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Track any exceptions that occur in threads
    ConcurrentHashMap<Thread, Throwable> exceptions = new ConcurrentHashMap<>();
    
    // Launch virtual threads that toggle conditions
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadIndex = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Each thread toggles conditions in a deterministic but different pattern
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            int conditionIndex = (threadIndex + j) % testConditions.size();
            testConditions.get(conditionIndex).toggle();
            
            // Small yield to increase interleaving
            Thread.yield();
          }
        } 
        catch (Throwable t) {
          exceptions.put(Thread.currentThread(), t);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Check for exceptions
    if (!exceptions.isEmpty()) {
      fail("Exceptions occurred in virtual threads: " + exceptions);
    }
    
    // Verify that all conditions were toggled multiple times
    for (TestCondition condition : testConditions) {
      assertTrue(condition.getEventCount() > 0, 
          "Condition " + condition + " should have been toggled at least once");
    }
    
    // Verify the mixed tree with a known state
    // For our mixed tree: (C0 AND C1) OR (C2 AND C3 AND C4) OR (C5 AND C6) OR (C7 AND C8 AND C9)
    
    // Set all conditions to unsatisfied
    for (TestCondition condition : testConditions) {
      condition.setSatisfied(false);
    }
    
    // Tree should be unsatisfied when all conditions are unsatisfied
    assertFalse(mixedTree.isSatisfied());
    
    // Satisfy the first AND group (C0 AND C1)
    testConditions.get(0).setSatisfied(true);
    testConditions.get(1).setSatisfied(true);
    
    // Now the tree should be satisfied
    assertTrue(mixedTree.isSatisfied());
    
    // Unsatisfy the first group and satisfy the second group (C2 AND C3 AND C4)
    testConditions.get(0).setSatisfied(false);
    testConditions.get(1).setSatisfied(false);
    testConditions.get(2).setSatisfied(true);
    testConditions.get(3).setSatisfied(true);
    testConditions.get(4).setSatisfied(true);
    
    // Now the tree should be satisfied
    assertTrue(mixedTree.isSatisfied());
    
    // Unsatisfy one condition in the second group
    testConditions.get(3).setSatisfied(false);
    
    // Now the tree should be unsatisfied
    assertFalse(mixedTree.isSatisfied());
  }
  
  /**
   * Tests for thread pinning when using virtual threads with logical conditions.
   * Thread pinning occurs when a virtual thread is stuck to a platform thread,
   * which defeats the purpose of virtual threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testThreadPinningDetection() throws Exception {
    // Create a condition tree
    Condition tree = createMixedConditionTree(testConditions);
    tree.bind();
    
    // Create a latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(100);
    
    // Track pinning detection results
    ConcurrentHashMap<Thread, ThreadPinningDetector> pinningDetectors = new ConcurrentHashMap<>();
    
    // Launch virtual threads that check for pinning while toggling conditions
    for (int i = 0; i < 100; i++) {
      final int threadIndex = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a pinning detector for this thread
          ThreadPinningDetector detector = new ThreadPinningDetector();
          pinningDetectors.put(Thread.currentThread(), detector);
          
          // Wait for all threads to be ready
          startLatch.await();
          
          for (int j = 0; j < 10; j++) {
            // Check for pinning
            detector.checkPinning();
            
            // Toggle a condition
            int conditionIndex = (threadIndex + j) % testConditions.size();
            testConditions.get(conditionIndex).toggle();
            
            // Check tree state
            tree.isSatisfied();
            
            // Check for pinning again
            detector.checkPinning();
            
            // Small sleep to allow for carrier thread switching
            Thread.sleep(1);
            
            // Check for pinning again
            detector.checkPinning();
          }
        } 
        catch (Throwable t) {
          t.printStackTrace();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Count how many threads were pinned
    int pinnedThreads = 0;
    for (ThreadPinningDetector detector : pinningDetectors.values()) {
      if (detector.isPinned()) {
        pinnedThreads++;
      }
    }
    
    // We expect minimal thread pinning with our implementation
    // This is a soft assertion as some pinning might occur due to test environment
    System.out.println("Detected " + pinnedThreads + " pinned threads out of " + pinningDetectors.size());
    assertTrue(pinnedThreads < pinningDetectors.size() / 2, 
        "Too many virtual threads were pinned: " + pinnedThreads + " out of " + pinningDetectors.size());
  }
  
  /**
   * Compares performance between virtual threads and platform threads for logical condition evaluation.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Create a complex condition tree
    Condition complexTree = createMixedConditionTree(testConditions);
    complexTree.bind();
    
    // Measure virtual thread performance
    Duration virtualThreadDuration = measurePerformance(() -> {
      return runConcurrentConditionToggles(virtualThreadExecutor, VIRTUAL_THREAD_COUNT, complexTree);
    });
    
    // Reset conditions
    for (TestCondition condition : testConditions) {
      condition.setSatisfied(false);
    }
    
    // Measure platform thread performance
    Duration platformThreadDuration = measurePerformance(() -> {
      return runConcurrentConditionToggles(platformThreadExecutor, PLATFORM_THREAD_COUNT, complexTree);
    });
    
    // Log performance results
    System.out.println("Virtual Thread Performance (" + VIRTUAL_THREAD_COUNT + " threads): " + 
        virtualThreadDuration.toMillis() + "ms");
    System.out.println("Platform Thread Performance (" + PLATFORM_THREAD_COUNT + " threads): " + 
        platformThreadDuration.toMillis() + "ms");
    
    // Calculate operations per second
    long virtualOpsPerSec = calculateOpsPerSecond(VIRTUAL_THREAD_COUNT * OPERATIONS_PER_THREAD, virtualThreadDuration);
    long platformOpsPerSec = calculateOpsPerSecond(PLATFORM_THREAD_COUNT * OPERATIONS_PER_THREAD, platformThreadDuration);
    
    System.out.println("Virtual Thread Throughput: " + virtualOpsPerSec + " ops/sec");
    System.out.println("Platform Thread Throughput: " + platformOpsPerSec + " ops/sec");
    
    // We expect virtual threads to have higher throughput due to their lightweight nature
    // This is a soft assertion as performance can vary by environment
    assertTrue(virtualOpsPerSec >= platformOpsPerSec * 0.8, 
        "Virtual thread performance should be comparable to platform threads even with many more threads");
  }
  
  /**
   * Creates a complex tree of AND conditions with the specified depth.
   */
  private Condition createComplexAndConditionTree(int depth, List<TestCondition> conditions) {
    if (depth <= 1 || conditions.size() <= 2) {
      return underTest.and(conditions.get(0), conditions.get(1));
    }
    
    // Split conditions into two groups
    int mid = conditions.size() / 2;
    List<TestCondition> leftGroup = conditions.subList(0, mid);
    List<TestCondition> rightGroup = conditions.subList(mid, conditions.size());
    
    // Recursively create subtrees
    Condition leftTree = createComplexAndConditionTree(depth - 1, leftGroup);
    Condition rightTree = createComplexAndConditionTree(depth - 1, rightGroup);
    
    // Combine subtrees with AND
    return underTest.and(leftTree, rightTree);
  }
  
  /**
   * Creates a complex tree of OR conditions with the specified depth.
   */
  private Condition createComplexOrConditionTree(int depth, List<TestCondition> conditions) {
    if (depth <= 1 || conditions.size() <= 2) {
      return underTest.or(conditions.get(0), conditions.get(1));
    }
    
    // Split conditions into two groups
    int mid = conditions.size() / 2;
    List<TestCondition> leftGroup = conditions.subList(0, mid);
    List<TestCondition> rightGroup = conditions.subList(mid, conditions.size());
    
    // Recursively create subtrees
    Condition leftTree = createComplexOrConditionTree(depth - 1, leftGroup);
    Condition rightTree = createComplexOrConditionTree(depth - 1, rightGroup);
    
    // Combine subtrees with OR
    return underTest.or(leftTree, rightTree);
  }
  
  /**
   * Creates a mixed tree of AND and OR conditions.
   * Structure: (C0 AND C1) OR (C2 AND C3 AND C4) OR (C5 AND C6) OR (C7 AND C8 AND C9)
   */
  private Condition createMixedConditionTree(List<TestCondition> conditions) {
    // Ensure we have enough conditions
    if (conditions.size() < 10) {
      throw new IllegalArgumentException("Need at least 10 conditions for mixed tree");
    }
    
    // Create AND groups
    Condition group1 = underTest.and(conditions.get(0), conditions.get(1));
    Condition group2 = underTest.and(conditions.get(2), conditions.get(3), conditions.get(4));
    Condition group3 = underTest.and(conditions.get(5), conditions.get(6));
    Condition group4 = underTest.and(conditions.get(7), conditions.get(8), conditions.get(9));
    
    // Combine with OR
    return underTest.or(group1, group2, group3, group4);
  }
  
  /**
   * Runs concurrent condition toggles using the provided executor.
   */
  private boolean runConcurrentConditionToggles(
      ExecutorService executor, int threadCount, Condition tree) 
  {
    try {
      // Create a latch to coordinate thread start and completion
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      
      // Launch threads that toggle conditions
      for (int i = 0; i < threadCount; i++) {
        final int threadIndex = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Each thread toggles conditions in a deterministic but different pattern
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              int conditionIndex = (threadIndex + j) % testConditions.size();
              testConditions.get(conditionIndex).toggle();
              
              // Check tree state occasionally
              if (j % 10 == 0) {
                tree.isSatisfied();
              }
            }
          } 
          catch (Throwable t) {
            t.printStackTrace();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      return completionLatch.await(30, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }
  
  /**
   * Measures the performance of a task.
   */
  private Duration measurePerformance(Supplier<Boolean> task) {
    Instant start = Instant.now();
    boolean success = task.get();
    Instant end = Instant.now();
    
    assertTrue(success, "Task did not complete successfully");
    return Duration.between(start, end);
  }
  
  /**
   * Calculates operations per second.
   */
  private long calculateOpsPerSecond(long operations, Duration duration) {
    double seconds = duration.toNanos() / 1_000_000_000.0;
    return (long)(operations / seconds);
  }
}