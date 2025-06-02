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
package virtualthread;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.sonatype.nexus.content.testsuite.groups.VirtualThreadTestSupport;
import org.sonatype.nexus.pax.logging.NexusLogFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.sonatype.nexus.logging.task.TaskLogger.TASK_LOG_ONLY_MDC;
import static org.sonatype.nexus.logging.task.TaskLogger.TASK_LOG_WITH_PROGRESS_MDC;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.*;

/**
 * Tests the {@link NexusLogFilter} functionality specifically in a virtual thread environment
 * to ensure that SLF4J MDC keys and Logback Markers are correctly propagated and handled
 * across thread boundaries when using Java 21 virtual threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
public class VirtualThreadNexusLogFilterTest
    extends VirtualThreadTestSupport
{
  private NexusLogFilter excludeProgressLogsFilter;

  private ILoggingEvent event;

  @BeforeEach
  public void setup() {
    // Ensure virtual threads are supported in the current JVM
    assumeVirtualThreadSupported();
    
    excludeProgressLogsFilter = new NexusLogFilter();
    event = new LoggingEvent();
    
    // Configure Awaitility default settings
    Awaitility.setDefaultPollInterval(50, TimeUnit.MILLISECONDS);
    Awaitility.setDefaultTimeout(5, SECONDS);
  }

  @AfterEach
  public void tearDown() {
    MDC.clear(); // Clear all MDC entries to avoid test interference
  }

  /**
   * Tests that the basic filter functionality works correctly in a virtual thread.
   */
  @Test
  public void testBasicFilterInVirtualThread() throws Exception {
    AtomicReference<FilterReply> result = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("basic-filter-test").start(() -> {
      result.set(excludeProgressLogsFilter.decide(event));
    });
    
    virtualThread.join();
    
    assertThat(result.get(), equalTo(NEUTRAL));
  }

  /**
   * Tests that marker-based filtering works correctly in a virtual thread.
   */
  @Test
  public void testMarkerFilteringInVirtualThread() throws Exception {
    AtomicReference<FilterReply> progressResult = new AtomicReference<>();
    AtomicReference<FilterReply> taskResult = new AtomicReference<>();
    AtomicReference<FilterReply> auditResult = new AtomicReference<>();
    AtomicReference<FilterReply> customResult = new AtomicReference<>();
    
    CountDownLatch latch = new CountDownLatch(4);
    
    // Test PROGRESS marker in virtual thread
    Thread progressThread = Thread.ofVirtual().name("progress-marker-thread").start(() -> {
      try {
        progressResult.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(PROGRESS)));
      } finally {
        latch.countDown();
      }
    });
    
    // Test TASK_LOG_ONLY marker in virtual thread
    Thread taskThread = Thread.ofVirtual().name("task-marker-thread").start(() -> {
      try {
        taskResult.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(TASK_LOG_ONLY)));
      } finally {
        latch.countDown();
      }
    });
    
    // Test AUDIT_LOG_ONLY marker in virtual thread
    Thread auditThread = Thread.ofVirtual().name("audit-marker-thread").start(() -> {
      try {
        auditResult.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(AUDIT_LOG_ONLY)));
      } finally {
        latch.countDown();
      }
    });
    
    // Test custom marker in virtual thread
    Thread customThread = Thread.ofVirtual().name("custom-marker-thread").start(() -> {
      try {
        Marker customMarker = MarkerFactory.getMarker("custom");
        customResult.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(customMarker)));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for all threads to complete
    latch.await(5, SECONDS);
    
    // Verify results
    assertThat(progressResult.get(), equalTo(DENY));
    assertThat(taskResult.get(), equalTo(DENY));
    assertThat(auditResult.get(), equalTo(DENY));
    assertThat(customResult.get(), equalTo(NEUTRAL));
  }

  /**
   * Tests that MDC values are correctly propagated to virtual threads and
   * that the filter correctly uses these values for decision making.
   */
  @Test
  public void testMDCPropagationToVirtualThread() throws Exception {
    AtomicReference<FilterReply> result = new AtomicReference<>();
    
    // Set MDC in parent thread
    MDC.put(TASK_LOG_ONLY_MDC, "test-value");
    
    Thread virtualThread = Thread.ofVirtual().name("mdc-propagation-thread").start(() -> {
      // Verify MDC value is propagated to virtual thread
      String mdcValue = MDC.get(TASK_LOG_ONLY_MDC);
      assertNotNull(mdcValue, "MDC value should be propagated to virtual thread");
      assertEquals("test-value", mdcValue, "MDC value should match parent thread value");
      
      // Test filter with propagated MDC
      result.set(excludeProgressLogsFilter.decide(event));
    });
    
    virtualThread.join();
    
    // Verify filter result based on MDC
    assertThat(result.get(), equalTo(DENY));
  }

  /**
   * Tests that MDC values set in a virtual thread are isolated to that thread
   * and don't affect other concurrent virtual threads.
   */
  @Test
  public void testMDCIsolationBetweenVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(2);
    
    AtomicReference<FilterReply> thread1Result = new AtomicReference<>();
    AtomicReference<FilterReply> thread2Result = new AtomicReference<>();
    AtomicReference<String> thread2MdcValue = new AtomicReference<>();
    
    // Thread 1 sets MDC and tests filter
    Thread thread1 = Thread.ofVirtual().name("mdc-thread-1").start(() -> {
      try {
        // Wait for signal to start
        startLatch.await();
        
        // Set MDC in this thread only
        MDC.put(TASK_LOG_ONLY_MDC, "thread1-value");
        
        // Test filter with thread-specific MDC
        thread1Result.set(excludeProgressLogsFilter.decide(event));
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } 
      finally {
        completionLatch.countDown();
      }
    });
    
    // Thread 2 should not see MDC from Thread 1
    Thread thread2 = Thread.ofVirtual().name("mdc-thread-2").start(() -> {
      try {
        // Wait for signal to start
        startLatch.await();
        
        // Check MDC value (should be null or empty)
        thread2MdcValue.set(MDC.get(TASK_LOG_ONLY_MDC));
        
        // Test filter without thread-specific MDC
        thread2Result.set(excludeProgressLogsFilter.decide(event));
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } 
      finally {
        completionLatch.countDown();
      }
    });
    
    // Signal threads to start
    startLatch.countDown();
    
    // Wait for both threads to complete
    completionLatch.await();
    
    // Verify thread isolation
    assertThat(thread1Result.get(), equalTo(DENY)); // Thread 1 should see DENY due to MDC
    assertThat(thread2Result.get(), equalTo(NEUTRAL)); // Thread 2 should see NEUTRAL due to no MDC
    assertThat(thread2MdcValue.get(), equalTo(null)); // Thread 2 should not see Thread 1's MDC
  }

  /**
   * Tests that the filter correctly handles the TASK_LOG_WITH_PROGRESS_MDC flag
   * in a virtual thread environment.
   */
  @Test
  public void testTaskLogWithProgressInVirtualThread() throws Exception {
    AtomicReference<FilterReply> result = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("progress-mdc-thread").start(() -> {
      // Set up MDC for progress
      MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
      MDC.put(TASK_LOG_ONLY_MDC, "anything"); // This should be overridden by TASK_LOG_WITH_PROGRESS_MDC
      
      // Test with INTERNAL_PROGRESS marker
      result.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)));
    });
    
    virtualThread.join();
    
    // Verify result - should be NEUTRAL because TASK_LOG_WITH_PROGRESS_MDC is set
    assertThat(result.get(), equalTo(NEUTRAL));
  }

  /**
   * Tests the filter with a high number of concurrent virtual threads to ensure
   * thread-safety and correct behavior under load.
   */
  @Test
  public void testConcurrentVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Thread> virtualThreads = new ArrayList<>();
    List<FilterReply> results = new ArrayList<>(threadCount);
    
    // Create and start multiple virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread thread = Thread.ofVirtual().name("concurrent-thread-" + threadId).start(() -> {
        try {
          // Set unique MDC value for each thread
          if (threadId % 3 == 0) {
            // Every third thread gets TASK_LOG_ONLY_MDC
            MDC.put(TASK_LOG_ONLY_MDC, "thread-" + threadId);
          } 
          else if (threadId % 3 == 1) {
            // Every third + 1 thread gets TASK_LOG_WITH_PROGRESS_MDC
            MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
          }
          // Remaining threads get no MDC
          
          // Test filter with thread-specific MDC
          synchronized (results) {
            results.add(excludeProgressLogsFilter.decide(event));
          }
        } 
        finally {
          MDC.clear();
          latch.countDown();
        }
      });
      virtualThreads.add(thread);
    }
    
    // Wait for all threads to complete
    latch.await();
    
    // Verify results
    int denyCount = 0;
    int neutralCount = 0;
    
    for (FilterReply reply : results) {
      if (reply == DENY) {
        denyCount++;
      } 
      else if (reply == NEUTRAL) {
        neutralCount++;
      }
    }
    
    // We expect approximately 1/3 of threads to return DENY (those with TASK_LOG_ONLY_MDC)
    // and 2/3 to return NEUTRAL (those with TASK_LOG_WITH_PROGRESS_MDC or no MDC)
    assertEquals(threadCount / 3, denyCount, threadCount / 10, "Expected approximately 1/3 of threads to return DENY");
    assertEquals(2 * threadCount / 3, neutralCount, threadCount / 10, "Expected approximately 2/3 of threads to return NEUTRAL");
  }

  /**
   * Tests asynchronous filter behavior using Awaitility to handle the asynchronous nature
   * of virtual threads.
   */
  @Test
  public void testAsyncFilterBehaviorWithAwaitility() {
    // Set up a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task that will eventually set MDC and affect filter behavior
      executor.submit(() -> {
        try {
          // Simulate some async processing
          Thread.sleep(500);
          // Set MDC that should cause filter to return DENY
          MDC.put(TASK_LOG_ONLY_MDC, "async-value");
        } 
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      
      // Use Awaitility to wait for the filter to start returning DENY
      // This tests that our async MDC change is correctly detected
      await().atMost(5, SECONDS)
          .pollInterval(100, TimeUnit.MILLISECONDS)
          .untilAsserted(() -> {
            // This will run in a separate thread each time
            FilterReply reply = excludeProgressLogsFilter.decide(event);
            assertThat(reply, equalTo(DENY));
          });
    }
  }

  /**
   * Helper method to create a logging event with the specified marker.
   */
  private ILoggingEvent eventWithMarkerOf(final Marker marker) {
    LoggingEvent event = new LoggingEvent();
    event.setMessage("Test Message");
    event.setMarker(marker);
    return event;
  }
}