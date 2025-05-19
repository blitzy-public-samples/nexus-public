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
package org.sonatype.nexus.virtualthread;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sonatype.nexus.common.event.EventBusFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link EventBusFactory} with Virtual Threads.
 * 
 * @since 3.60.0
 */
@Category(Java21TestGroup.class)
public class EventBusVirtualThreadTest
{
  private EventBus eventBus;
  
  private List<String> recorded;
  
  private CountDownLatch latch;
  
  private static class TestEvent
  {
    private final String message;
    
    public TestEvent(String message) {
      this.message = message;
    }
    
    public String getMessage() {
      return message;
    }
  }
  
  private class TestSubscriber
  {
    @Subscribe
    public void onEvent(TestEvent event) throws InterruptedException {
      // Record that we're processing the event
      recorded.add("Processing: " + event.getMessage());
      
      // Simulate some I/O work that would benefit from Virtual Threads
      Thread.sleep(100);
      
      // Record thread information
      Thread currentThread = Thread.currentThread();
      recorded.add("Thread: " + currentThread.getName() + ", isVirtual: " + isVirtualThread(currentThread));
      
      // Signal that we're done
      latch.countDown();
    }
  }
  
  @Before
  public void setUp() {
    recorded = new ArrayList<>();
  }
  
  @After
  public void tearDown() {
    if (eventBus != null) {
      // Clean up subscribers
      eventBus = null;
    }
  }
  
  @Test
  public void testVirtualThreadEventBus() throws Exception {
    // Skip test if not running on Java 21+
    org.junit.Assume.assumeTrue("Test requires Java 21+ for Virtual Threads", isJava21OrHigher());
    
    // Create event bus with virtual threads
    eventBus = EventBusFactory.reentrantVirtualThreadEventBus("test-virtual-thread-bus");
    
    // Set up latch for 3 events
    latch = new CountDownLatch(3);
    
    // Register subscriber
    TestSubscriber subscriber = new TestSubscriber();
    eventBus.register(subscriber);
    
    // Post multiple events to demonstrate concurrent processing
    eventBus.post(new TestEvent("Event 1"));
    eventBus.post(new TestEvent("Event 2"));
    eventBus.post(new TestEvent("Event 3"));
    
    // Wait for all events to be processed
    boolean completed = latch.await(1, TimeUnit.SECONDS);
    assertThat("All events should be processed", completed, is(true));
    
    // Verify that events were processed
    assertThat(recorded.contains("Processing: Event 1"), is(true));
    assertThat(recorded.contains("Processing: Event 2"), is(true));
    assertThat(recorded.contains("Processing: Event 3"), is(true));
    
    // Check that at least one thread was a virtual thread
    boolean foundVirtualThread = recorded.stream()
        .anyMatch(s -> s.contains("isVirtual: true"));
    
    assertThat("Should find at least one virtual thread", foundVirtualThread, is(true));
  }
  
  /**
   * Checks if the current thread is a virtual thread.
   * Uses reflection to avoid direct dependency on Java 21 API.
   */
  private boolean isVirtualThread(Thread thread) {
    try {
      Method isVirtualMethod = Thread.class.getMethod("isVirtual");
      return (boolean) isVirtualMethod.invoke(thread);
    }
    catch (Exception e) {
      return false;
    }
  }
  
  /**
   * Checks if running on Java 21 or higher.
   */
  private boolean isJava21OrHigher() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      // Old version format: 1.8.x
      return false;
    }
    else {
      // New version format: 9, 10, 11, etc.
      int majorVersion = Integer.parseInt(version.split("\\.")[0]);
      return majorVersion >= 21;
    }
  }
}