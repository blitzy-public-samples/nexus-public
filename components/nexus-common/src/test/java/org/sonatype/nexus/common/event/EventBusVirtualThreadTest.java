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
package org.sonatype.nexus.common.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.sonatype.nexus.common.event.EventBusFactory.reentrantEventBus;

/**
 * Tests EventBus behavior when using Java 21 Virtual Threads.
 * 
 * This test class validates that both standard and reentrant event buses properly handle events
 * dispatched from virtual threads, including correct ordering, thread context propagation,
 * and handling of high concurrency loads.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class EventBusVirtualThreadTest
{
  private static final int HIGH_CONCURRENCY_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 10;
  
  private EventBus eventBus;
  private List<String> recorded;
  private ThreadLocal<String> threadContext;

  @Before
  public void setUp() {
    recorded = Collections.synchronizedList(new ArrayList<>());
    threadContext = new ThreadLocal<>();
  }

  @After
  public void tearDown() {
    recorded = null;
    threadContext = null;
  }

  synchronized void recordEnter(Object event) {
    recorded.add(event.getClass().getSimpleName() + " -->");
  }

  synchronized void recordLeave(Object event) {
    recorded.add("<-- " + event.getClass().getSimpleName());
  }

  /**
   * Verifies that standard EventBus maintains expected behavior when events are posted from virtual threads.
   */
  @Test
  public void verifyStandardEventBusWithVirtualThread() throws Exception {
    eventBus = new EventBus();
    eventBus.register(new Subscriber1());
    eventBus.register(new Subscriber2());
    
    // Post event from a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("virtual-event-poster").start(() -> {
      eventBus.post(new EventA());
    });
    
    virtualThread.join();

    // Standard EventBus should maintain the same behavior as with platform threads
    assertThat(recorded, contains("EventA -->", "<-- EventA", "EventB -->", "<-- EventB", "EventC -->", "<-- EventC"));
  }

  /**
   * Verifies that reentrant EventBus maintains expected behavior when events are posted from virtual threads.
   */
  @Test
  public void verifyReentrantEventBusWithVirtualThread() throws Exception {
    eventBus = reentrantEventBus("test");
    eventBus.register(new Subscriber1());
    eventBus.register(new Subscriber2());
    
    // Post event from a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("virtual-event-poster").start(() -> {
      eventBus.post(new EventA());
    });
    
    virtualThread.join();

    // Reentrant EventBus should maintain the same behavior as with platform threads
    assertThat(recorded, contains("EventA -->", "EventB -->", "EventC -->", "<-- EventC", "<-- EventB", "<-- EventA"));
  }
  
  /**
   * Tests that thread-local context is properly maintained when events are posted from virtual threads.
   */
  @Test
  public void verifyThreadLocalContextPropagation() throws Exception {
    eventBus = new EventBus();
    ContextAwareSubscriber subscriber = new ContextAwareSubscriber();
    eventBus.register(subscriber);
    
    // Set thread-local context and post event from a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("context-aware-poster").start(() -> {
      threadContext.set("virtual-thread-context");
      eventBus.post(new ContextEvent());
    });
    
    virtualThread.join();
    
    // Verify the context was properly propagated
    assertThat(subscriber.getReceivedContext(), is("virtual-thread-context"));
  }
  
  /**
   * Tests high concurrency scenario with multiple virtual threads posting events simultaneously.
   */
  @Test
  public void verifyHighConcurrencyWithVirtualThreads() throws Exception {
    eventBus = new EventBus();
    CountingSubscriber subscriber = new CountingSubscriber();
    eventBus.register(subscriber);
    
    CountDownLatch latch = new CountDownLatch(HIGH_CONCURRENCY_COUNT);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit many concurrent event posting tasks
      for (int i = 0; i < HIGH_CONCURRENCY_COUNT; i++) {
        final int eventId = i;
        executor.submit(() -> {
          try {
            eventBus.post(new CountEvent(eventId));
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all events to be processed
      assertThat("Timed out waiting for events to be processed",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    // Verify all events were received
    assertThat(subscriber.getEventCount(), is(HIGH_CONCURRENCY_COUNT));
  }
  
  /**
   * Compares performance between virtual threads and platform threads for event posting.
   */
  @Test
  public void compareVirtualThreadVsPlatformThreadPerformance() throws Exception {
    eventBus = new EventBus();
    CountingSubscriber subscriber = new CountingSubscriber();
    eventBus.register(subscriber);
    
    int threadCount = 1000;
    CountDownLatch virtualLatch = new CountDownLatch(threadCount);
    CountDownLatch platformLatch = new CountDownLatch(threadCount);
    
    // Measure virtual thread performance
    long virtualStartTime = System.nanoTime();
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        virtualExecutor.submit(() -> {
          try {
            eventBus.post(new PerformanceEvent());
          } finally {
            virtualLatch.countDown();
          }
        });
      }
      virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    long virtualDuration = System.nanoTime() - virtualStartTime;
    
    // Reset subscriber
    subscriber.reset();
    
    // Measure platform thread performance
    long platformStartTime = System.nanoTime();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(Math.min(100, threadCount))) {
      for (int i = 0; i < threadCount; i++) {
        platformExecutor.submit(() -> {
          try {
            eventBus.post(new PerformanceEvent());
          } finally {
            platformLatch.countDown();
          }
        });
      }
      platformLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    long platformDuration = System.nanoTime() - platformStartTime;
    
    // Log performance results
    System.out.println("Virtual thread duration (ns): " + virtualDuration);
    System.out.println("Platform thread duration (ns): " + platformDuration);
    
    // Virtual threads should generally be more efficient for this I/O-bound workload
    // but we don't assert this as it might vary based on environment
  }
  
  /**
   * Tests that event ordering is maintained when events are posted from multiple virtual threads.
   */
  @Test
  public void verifyEventOrderingWithVirtualThreads() throws Exception {
    eventBus = reentrantEventBus("ordered-test");
    OrderedSubscriber subscriber = new OrderedSubscriber();
    eventBus.register(subscriber);
    
    int eventCount = 100;
    CountDownLatch latch = new CountDownLatch(eventCount);
    
    // Create a sequence of ordered events
    List<OrderedEvent> expectedEvents = new ArrayList<>();
    for (int i = 0; i < eventCount; i++) {
      expectedEvents.add(new OrderedEvent(i));
    }
    
    // Post events from virtual threads in a potentially different order
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (OrderedEvent event : expectedEvents) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          // Add some randomness to execution order
          try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(10));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          
          eventBus.post(event);
          latch.countDown();
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all events to be posted
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    // Verify events were received in the correct order by ID
    List<Integer> receivedIds = subscriber.getReceivedEventIds();
    assertThat(receivedIds, hasSize(eventCount));
    
    // Check that IDs are in ascending order
    for (int i = 0; i < receivedIds.size() - 1; i++) {
      assertThat(receivedIds.get(i), is(i));
    }
  }

  // Event classes
  static class EventA {
    // empty
  }

  static class EventB {
    // empty
  }

  static class EventC {
    // empty
  }
  
  static class ContextEvent {
    // empty
  }
  
  static class CountEvent {
    private final int id;
    
    CountEvent(int id) {
      this.id = id;
    }
    
    public int getId() {
      return id;
    }
  }
  
  static class PerformanceEvent {
    // empty
  }
  
  static class OrderedEvent {
    private final int id;
    
    OrderedEvent(int id) {
      this.id = id;
    }
    
    public int getId() {
      return id;
    }
  }

  // Subscriber classes
  class Subscriber1 {
    @Subscribe
    public void on(EventA event) {
      recordEnter(event);
      eventBus.post(new EventB());
      recordLeave(event);
    }

    @Subscribe
    public void on(EventC event) {
      recordEnter(event);
      recordLeave(event);
    }
  }

  class Subscriber2 {
    @Subscribe
    public void on(EventB event) {
      recordEnter(event);
      eventBus.post(new EventC());
      recordLeave(event);
    }
  }
  
  class ContextAwareSubscriber {
    private String receivedContext;
    
    @Subscribe
    public void on(ContextEvent event) {
      // Capture the thread-local context when event is received
      receivedContext = threadContext.get();
    }
    
    public String getReceivedContext() {
      return receivedContext;
    }
  }
  
  class CountingSubscriber {
    private final AtomicInteger eventCount = new AtomicInteger(0);
    private final ConcurrentMap<Integer, Boolean> receivedEvents = new ConcurrentHashMap<>();
    
    @Subscribe
    public void on(CountEvent event) {
      eventCount.incrementAndGet();
      receivedEvents.put(event.getId(), true);
    }
    
    @Subscribe
    public void on(PerformanceEvent event) {
      eventCount.incrementAndGet();
    }
    
    public int getEventCount() {
      return eventCount.get();
    }
    
    public void reset() {
      eventCount.set(0);
      receivedEvents.clear();
    }
  }
  
  class OrderedSubscriber {
    private final List<Integer> receivedEventIds = Collections.synchronizedList(new ArrayList<>());
    
    @Subscribe
    public void on(OrderedEvent event) {
      receivedEventIds.add(event.getId());
    }
    
    public List<Integer> getReceivedEventIds() {
      return receivedEventIds;
    }
  }
}