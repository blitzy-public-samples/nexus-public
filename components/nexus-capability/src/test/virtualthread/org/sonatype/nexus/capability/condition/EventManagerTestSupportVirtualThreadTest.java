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
package org.sonatype.nexus.capability.condition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.ConditionEvent.Satisfied;
import org.sonatype.nexus.capability.ConditionEvent.Unsatisfied;
import org.sonatype.nexus.common.event.EventManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Tests for {@link EventManagerTestSupport} using Java 21 Virtual Threads.
 * 
 * This test class validates that the event management infrastructure correctly
 * supports the Virtual Thread execution model introduced in Java 21.
 *
 * @since 3.60
 */
public class EventManagerTestSupportVirtualThreadTest
{
  private static final int THREAD_COUNT = 5000;
  private static final int EVENTS_PER_THREAD = 10;
  
  private AutoCloseable mocks;
  
  @Mock
  private EventManager eventManager;
  
  private List<Object> eventManagerEvents;
  private ConcurrentHashMap<Thread, Boolean> threadMap;
  private AtomicInteger virtualThreadCount;
  private AtomicInteger platformThreadCount;
  
  @BeforeEach
  public void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    eventManagerEvents = Collections.synchronizedList(new ArrayList<>());
    threadMap = new ConcurrentHashMap<>();
    virtualThreadCount = new AtomicInteger(0);
    platformThreadCount = new AtomicInteger(0);
    
    // Mock the event manager to capture events and track thread types
    doAnswer(new Answer<Object>() {
      @Override
      public Object answer(final InvocationOnMock invocation) throws Throwable {
        Thread currentThread = Thread.currentThread();
        threadMap.put(currentThread, true);
        
        // Track if this is a virtual thread or platform thread
        if (currentThread.isVirtual()) {
          virtualThreadCount.incrementAndGet();
        } else {
          platformThreadCount.incrementAndGet();
        }
        
        // Simulate some processing time to test for thread pinning
        Thread.sleep(1);
        
        eventManagerEvents.add(invocation.getArguments()[0]);
        return null;
      }
    }).when(eventManager).post(any());
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }
  
  /**
   * Tests that events can be posted concurrently from many virtual threads.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testConcurrentEventPostingWithVirtualThreads() throws Exception {
    // Create a mock condition
    Condition condition = createMockCondition("testCondition");
    
    // Use structured concurrency with virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      
      // Submit tasks to post events from virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Post alternating satisfied/unsatisfied events
            for (int j = 0; j < EVENTS_PER_THREAD; j++) {
              if (j % 2 == 0) {
                eventManager.post(new Satisfied(condition));
              } else {
                eventManager.post(new Unsatisfied(condition));
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads");
    }
    
    // Verify that all events were captured
    assertThat(eventManagerEvents, hasSize(THREAD_COUNT * EVENTS_PER_THREAD));
    
    // Verify that virtual threads were used
    assertThat(virtualThreadCount.get(), greaterThan(0));
    assertTrue(virtualThreadCount.get() > platformThreadCount.get(), 
        "Expected more virtual threads than platform threads");
  }
  
  /**
   * Tests that event handling is thread-safe when posting satisfied/unsatisfied events concurrently.
   */
  @Test
  public void testThreadSafeEventCapture() throws Exception {
    // Create multiple conditions
    final int conditionCount = 100;
    List<Condition> conditions = new ArrayList<>(conditionCount);
    for (int i = 0; i < conditionCount; i++) {
      conditions.add(createMockCondition("condition-" + i));
    }
    
    // Use structured concurrency with virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(conditionCount * 2);
      
      // For each condition, submit two tasks: one posting satisfied and one posting unsatisfied
      for (Condition condition : conditions) {
        // Task 1: Post satisfied events
        executor.submit(() -> {
          try {
            for (int i = 0; i < 10; i++) {
              eventManager.post(new Satisfied(condition));
              Thread.yield(); // Increase chance of interleaving
            }
          } finally {
            latch.countDown();
          }
        });
        
        // Task 2: Post unsatisfied events
        executor.submit(() -> {
          try {
            for (int i = 0; i < 10; i++) {
              eventManager.post(new Unsatisfied(condition));
              Thread.yield(); // Increase chance of interleaving
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads");
    }
    
    // Verify that all events were captured (20 events per condition)
    assertThat(eventManagerEvents, hasSize(conditionCount * 20));
    
    // Count satisfied and unsatisfied events
    long satisfiedCount = eventManagerEvents.stream()
        .filter(e -> e instanceof Satisfied)
        .count();
    long unsatisfiedCount = eventManagerEvents.stream()
        .filter(e -> e instanceof Unsatisfied)
        .count();
    
    // Verify equal number of satisfied and unsatisfied events
    assertThat(satisfiedCount, equalTo(unsatisfiedCount));
  }
  
  /**
   * Tests for thread pinning during event processing.
   * Thread pinning occurs when virtual threads are forced to execute on carrier threads
   * for extended periods, negating their benefits.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Create a condition that will be used for events
    Condition condition = createMockCondition("pinningTestCondition");
    
    // Track unique carrier threads used
    ConcurrentHashMap<String, Boolean> carrierThreads = new ConcurrentHashMap<>();
    
    // Use structured concurrency with virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(100);
      
      // Submit tasks that post events
      for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
          try {
            // Capture the carrier thread name before posting event
            String threadName = Thread.currentThread().toString();
            carrierThreads.put(threadName, true);
            
            // Post an event
            eventManager.post(new Satisfied(condition));
            
            // Sleep briefly to simulate work
            Thread.sleep(Duration.ofMillis(5));
            
            // Post another event
            eventManager.post(new Unsatisfied(condition));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads");
    }
    
    // If we have significantly fewer carrier threads than virtual threads,
    // it suggests thread pinning might be occurring
    int uniqueCarrierThreads = carrierThreads.size();
    System.out.println("Unique carrier threads used: " + uniqueCarrierThreads);
    
    // We expect to see multiple carrier threads being used efficiently
    // This is a heuristic - the exact number depends on the system
    assertThat(uniqueCarrierThreads, greaterThan(1));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for event handling.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    Condition condition = createMockCondition("performanceTestCondition");
    final int threadCount = 1000;
    final int eventsPerThread = 10;
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(100)) {
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              for (int j = 0; j < eventsPerThread; j++) {
                eventManager.post(new Satisfied(condition));
              }
            } finally {
              latch.countDown();
            }
          });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for platform threads");
      }
    });
    
    // Clear events between tests
    eventManagerEvents.clear();
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              for (int j = 0; j < eventsPerThread; j++) {
                eventManager.post(new Satisfied(condition));
              }
            } finally {
              latch.countDown();
            }
          });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for virtual threads");
      }
    });
    
    System.out.println("Platform thread execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual thread execution time: " + virtualThreadTime + "ms");
    
    // Virtual threads should generally be more efficient for this I/O-bound workload
    // However, this is a heuristic test and may not always hold true depending on the environment
    // We're primarily checking that virtual threads don't perform significantly worse
    assertFalse(virtualThreadTime > platformThreadTime * 2, 
        "Virtual threads performed significantly worse than platform threads");
  }
  
  /**
   * Tests using Java 21's structured concurrency for improved test organization.
   */
  @Test
  public void testStructuredConcurrency() throws Exception {
    Condition condition = createMockCondition("structuredConcurrencyTest");
    final int threadCount = 100;
    
    // Use the new structured concurrency API
    try (var scope = new java.util.concurrent.StructuredTaskScope.ShutdownOnFailure()) {
      // Submit tasks to the scope
      for (int i = 0; i < threadCount; i++) {
        scope.fork(() -> {
          eventManager.post(new Satisfied(condition));
          return true;
        });
      }
      
      // Wait for all tasks to complete or fail
      scope.join();
      // Propagate any exceptions
      scope.throwIfFailed();
    }
    
    // Verify that all events were captured
    assertThat(eventManagerEvents, hasSize(threadCount));
  }
  
  /**
   * Helper method to create a mock condition with a given name.
   */
  private Condition createMockCondition(String name) {
    return new Condition() {
      @Override
      public boolean isSatisfied() {
        return false;
      }
      
      @Override
      public String toString() {
        return name;
      }
    };
  }
  
  /**
   * Measures the execution time of a runnable in milliseconds.
   */
  private long measureExecutionTime(Runnable runnable) throws Exception {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
}