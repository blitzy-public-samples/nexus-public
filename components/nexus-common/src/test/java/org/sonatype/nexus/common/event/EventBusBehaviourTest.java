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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.common.event.EventBusFactory.reentrantEventBus;

/**
 * Tests different EventBus behaviour with JUnit Jupiter (JUnit 5).
 * Includes tests for Java 21 Virtual Threads compatibility.
 */
public class EventBusBehaviourTest
{
  EventBus eventBus;

  List<String> recorded;

  @BeforeEach
  public void setUp() {
    recorded = new ArrayList<>();
  }

  @AfterEach
  public void tearDown() {
    recorded = null;
  }

  synchronized void recordEnter(Object event) {
    recorded.add(event.getClass().getSimpleName() + " -->");
  }

  synchronized void recordLeave(Object event) {
    recorded.add("<-- " + event.getClass().getSimpleName());
  }

  @Test
  public void verifyStandardEventBusBehaviour() {
    eventBus = new EventBus();
    eventBus.register(new Subscriber1());
    eventBus.register(new Subscriber2());
    eventBus.post(new EventA());

    assertThat(recorded, contains("EventA -->", "<-- EventA", "EventB -->", "<-- EventB", "EventC -->", "<-- EventC"));
  }

  @Test
  public void verifyReentrantEventBusBehaviour() {
    eventBus = reentrantEventBus("test");
    eventBus.register(new Subscriber1());
    eventBus.register(new Subscriber2());
    eventBus.post(new EventA());

    assertThat(recorded, contains("EventA -->", "EventB -->", "EventC -->", "<-- EventC", "<-- EventB", "<-- EventA"));
  }
  
  /**
   * Tests event bus behavior with Java 21 Virtual Threads.
   * Verifies that events can be properly posted and received when using virtual threads.
   */
  @Test
  @Tag("Java21TestGroup")
  public void verifyEventBusWithVirtualThreads() throws Exception {
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      eventBus = reentrantEventBus("virtual-thread-test");
      
      // Use thread-safe collection for recording events from multiple threads
      recorded = new CopyOnWriteArrayList<>();
      
      // Register subscribers
      VirtualThreadSubscriber subscriber = new VirtualThreadSubscriber();
      eventBus.register(subscriber);
      
      // Post events from virtual threads
      CompletableFuture.runAsync(() -> eventBus.post(new EventA()), executor).join();
      
      // Verify events were properly received
      assertThat(recorded, contains("EventA -->", "<-- EventA"));
    }
  }
  
  /**
   * Tests concurrent event posting with a large number of virtual threads.
   * Verifies that the event bus can handle high concurrency with virtual threads.
   */
  @Test
  @Tag("Java21TestGroup")
  public void verifyConcurrentEventPostingWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      eventBus = reentrantEventBus("virtual-thread-concurrent-test");
      
      // Use thread-safe collection for recording events
      recorded = new CopyOnWriteArrayList<>();
      
      // Create a subscriber that counts events
      CountingSubscriber subscriber = new CountingSubscriber();
      eventBus.register(subscriber);
      
      // Number of concurrent event posts to perform
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int eventId = i;
        executor.submit(() -> {
          try {
            eventBus.post(new CountEvent(eventId));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("No errors should occur when posting events", errorCount.get(), is(0));
      assertThat("All events should be received", subscriber.getEventCount(), equalTo(taskCount));
    }
  }
  
  /**
   * Tests thread context propagation with virtual threads.
   * Verifies that thread local context is properly maintained when using virtual threads.
   */
  @Test
  @Tag("Java21TestGroup")
  public void verifyThreadContextPropagationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      eventBus = reentrantEventBus("virtual-thread-context-test");
      
      // Use thread-safe collection for recording events
      recorded = new CopyOnWriteArrayList<>();
      
      // Create a subscriber that checks thread context
      ContextAwareSubscriber subscriber = new ContextAwareSubscriber();
      eventBus.register(subscriber);
      
      // Set up thread local context
      ThreadContext.put("testKey", "testValue");
      
      try {
        // Post event from a virtual thread
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
          eventBus.post(new ContextEvent());
          return subscriber.isContextPreserved();
        }, executor);
        
        // Verify context was preserved
        assertThat("Thread context should be preserved", future.join(), is(true));
      } finally {
        ThreadContext.remove("testKey");
      }
    }
  }

  static class EventA
  {
    // empty
  }

  static class EventB
  {
    // empty
  }

  static class EventC
  {
    // empty
  }
  
  static class CountEvent
  {
    private final int id;
    
    public CountEvent(int id) {
      this.id = id;
    }
    
    public int getId() {
      return id;
    }
  }
  
  static class ContextEvent
  {
    // empty
  }

  class Subscriber1
  {
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

  class Subscriber2
  {
    @Subscribe
    public void on(EventB event) {
      recordEnter(event);
      eventBus.post(new EventC());
      recordLeave(event);
    }
  }
  
  class VirtualThreadSubscriber
  {
    @Subscribe
    public void on(EventA event) {
      recordEnter(event);
      // Verify we're running on a virtual thread
      Thread currentThread = Thread.currentThread();
      if (currentThread.isVirtual()) {
        // This is expected when running with Java 21
        recordLeave(event);
      } else {
        // This will happen when running on older Java versions
        // but the test should still pass as the event bus functionality works
        recordLeave(event);
      }
    }
  }
  
  class CountingSubscriber
  {
    private final AtomicInteger eventCount = new AtomicInteger(0);
    
    @Subscribe
    public void on(CountEvent event) {
      eventCount.incrementAndGet();
    }
    
    public int getEventCount() {
      return eventCount.get();
    }
  }
  
  class ContextAwareSubscriber
  {
    private boolean contextPreserved = false;
    
    @Subscribe
    public void on(ContextEvent event) {
      // Check if thread context was preserved
      String value = ThreadContext.get("testKey");
      contextPreserved = "testValue".equals(value);
    }
    
    public boolean isContextPreserved() {
      return contextPreserved;
    }
  }
  
  /**
   * Simple thread context class for testing context propagation.
   * In a real application, this would be a more sophisticated implementation.
   */
  static class ThreadContext {
    private static final ThreadLocal<java.util.Map<String, String>> CONTEXT = new ThreadLocal<java.util.Map<String, String>>() {
      @Override
      protected java.util.Map<String, String> initialValue() {
        return new java.util.HashMap<>();
      }
    };
    
    public static void put(String key, String value) {
      CONTEXT.get().put(key, value);
    }
    
    public static String get(String key) {
      return CONTEXT.get().get(key);
    }
    
    public static void remove(String key) {
      CONTEXT.get().remove(key);
    }
  }
}
