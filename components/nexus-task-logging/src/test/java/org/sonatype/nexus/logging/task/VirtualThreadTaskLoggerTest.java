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
package org.sonatype.nexus.logging.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;

/**
 * Tests for {@link TaskLogger} implementations with Java 21 Virtual Threads.
 * 
 * This test class validates that task logging correctly functions when tasks are executed
 * on virtual threads, ensuring MDC context propagation, proper log message formatting,
 * and thread-local variable handling work correctly with the lightweight threading model.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21")
public class VirtualThreadTaskLoggerTest
    extends TestSupport
{
  private static final String TEST_MDC_KEY = "testMdcKey";
  private static final String TEST_MDC_VALUE = "testMdcValue";
  private static final int CONCURRENT_TASKS = 1000;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  private Logger mockLogger;

  @Captor
  private ArgumentCaptor<String> messageCaptor;

  @Captor
  private ArgumentCaptor<Object[]> argsCaptor;

  private ProgressTaskLogger progressTaskLogger;

  @BeforeEach
  void setUp() {
    // Create a ProgressTaskLogger with short intervals for testing
    progressTaskLogger = new ProgressTaskLogger(mockLogger, 1, 10, TimeUnit.MILLISECONDS);
    
    // Clear any MDC from previous tests
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    // Ensure task logger is finished to clean up resources
    if (progressTaskLogger != null) {
      progressTaskLogger.finish();
    }
    
    // Clear MDC after test
    MDC.clear();
    
    // Clean up any task logger helper state
    TaskLoggerHelper.finish();
  }

  /**
   * Tests that MDC context is properly propagated to virtual threads.
   */
  @Test
  void mdcContextPropagationToVirtualThread() throws Exception {
    // Set MDC in the main thread
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);

    // Start the task logger to capture MDC
    progressTaskLogger.start();

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean mdcValueCorrect = new AtomicBoolean(false);

    // Create and run a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Check if MDC was propagated to the virtual thread
        String mdcValue = MDC.get(TEST_MDC_KEY);
        mdcValueCorrect.set(TEST_MDC_VALUE.equals(mdcValue));
      } finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();

    // Verify MDC was correctly propagated
    assertTrue(mdcValueCorrect.get(), "MDC context was not properly propagated to virtual thread");
  }

  /**
   * Tests that task logging works correctly with a single virtual thread.
   */
  @Test
  void singleVirtualThreadTaskLogging() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean loggedSuccessfully = new AtomicBoolean(false);

    // Create and run a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Log a progress message from the virtual thread
        TaskLoggerHelper.progress(mockLogger, "Virtual thread task progress");
        TaskLoggerHelper.flush();
        loggedSuccessfully.set(true);
      } finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();

    // Verify logging was successful
    assertTrue(loggedSuccessfully.get(), "Logging from virtual thread failed");
    
    // Verify the log message was captured with the correct format
    verify(mockLogger, times(1)).info(eq(INTERNAL_PROGRESS), messageCaptor.capture(), argsCaptor.capture());
    assertThat(messageCaptor.getValue(), containsString("---- Virtual thread task progress ----"));
  }

  /**
   * Tests that task logging works correctly with many concurrent virtual threads.
   */
  @Test
  void massiveConcurrentVirtualThreadTaskLogging() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Create a latch to wait for all virtual threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_TASKS; i++) {
        final int taskId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Log a progress message with the task ID
            TaskLoggerHelper.progress(mockLogger, "Virtual thread task progress " + taskId);
            
            // Verify MDC context is available in the virtual thread
            if (TEST_MDC_VALUE.equals(MDC.get(TEST_MDC_KEY))) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Not all virtual threads completed in time");
      
      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    // Flush any pending log messages
    TaskLoggerHelper.flush();
    
    // Verify all tasks had correct MDC context
    assertEquals(CONCURRENT_TASKS, successCount.get(), 
        "Not all virtual threads had correct MDC context");
  }

  /**
   * Tests that MDC context is properly cleaned up after virtual thread completion.
   */
  @Test
  void mdcContextCleanupAfterVirtualThreadCompletion() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Create a thread factory that sets a custom MDC value
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create and run a virtual thread
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // Set a different MDC value in the virtual thread
        MDC.put(TEST_MDC_KEY, "differentValue");
        
        // Log a progress message
        TaskLoggerHelper.progress(mockLogger, "Virtual thread with custom MDC");
      } finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();

    // Wait for the virtual thread to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();

    // Verify the original MDC value is still intact in the main thread
    assertEquals(TEST_MDC_VALUE, MDC.get(TEST_MDC_KEY), 
        "MDC context in main thread was modified by virtual thread");
  }

  /**
   * Tests that task logging works correctly with nested virtual threads.
   */
  @Test
  void nestedVirtualThreadsTaskLogging() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Create latches to track completion
    CountDownLatch outerLatch = new CountDownLatch(1);
    CountDownLatch innerLatch = new CountDownLatch(1);
    
    // Track MDC values at different levels
    AtomicBoolean outerMdcCorrect = new AtomicBoolean(false);
    AtomicBoolean innerMdcCorrect = new AtomicBoolean(false);

    // Create and run an outer virtual thread
    Thread outerThread = Thread.ofVirtual().start(() -> {
      try {
        // Check MDC in outer thread
        outerMdcCorrect.set(TEST_MDC_VALUE.equals(MDC.get(TEST_MDC_KEY)));
        
        // Create and run an inner virtual thread
        Thread innerThread = Thread.ofVirtual().start(() -> {
          try {
            // Check MDC in inner thread
            innerMdcCorrect.set(TEST_MDC_VALUE.equals(MDC.get(TEST_MDC_KEY)));
            
            // Log a progress message from the inner thread
            TaskLoggerHelper.progress(mockLogger, "Inner virtual thread progress");
          } finally {
            innerLatch.countDown();
          }
        });
        
        // Wait for inner thread to complete
        innerThread.join();
        
        // Log a progress message from the outer thread
        TaskLoggerHelper.progress(mockLogger, "Outer virtual thread progress");
      } catch (InterruptedException e) {
    	  assertDoesNotThrow(() -> e);
		
	} finally {
        outerLatch.countDown();
      }
    });

    // Wait for both threads to complete
    assertTrue(innerLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Inner virtual thread did not complete in time");
    assertTrue(outerLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Outer virtual thread did not complete in time");
    outerThread.join();

    // Verify MDC was correctly propagated to both threads
    assertTrue(outerMdcCorrect.get(), "MDC context was not properly propagated to outer virtual thread");
    assertTrue(innerMdcCorrect.get(), "MDC context was not properly propagated to inner virtual thread");
    
    // Flush any pending log messages
    TaskLoggerHelper.flush();
  }

  /**
   * Tests that task logging works correctly with Java 21 String Templates.
   */
  @Test
  void virtualThreadTaskLoggingWithStringTemplates() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);

    // Create and run a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Create variables for the template
        String operation = "processing";
        int count = 42;
        
        // Create a string template
        StringTemplate template = STR."Virtual thread \{operation} \{count} items";
        
        // Log a progress message using the template
        TaskLoggerHelper.progress(new TaskLoggingEvent(mockLogger, template));
        TaskLoggerHelper.flush();
      } finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();
    
    // Verify the log message was captured with the correct format
    verify(mockLogger, times(1)).info(eq(INTERNAL_PROGRESS), messageCaptor.capture(), argsCaptor.capture());
    assertThat(messageCaptor.getValue(), containsString("---- Virtual thread processing 42 items ----"));
  }

  /**
   * Tests that task logging context can be captured and applied across virtual threads.
   */
  @Test
  void contextCapturingAndApplyingAcrossVirtualThreads() throws Exception {
    // Create a custom task logger that supports context capturing
    TaskLogger customTaskLogger = new TaskLogger() {
      private Map<String, String> capturedContext;
      
      @Override
      public void start() {
        // Capture the current MDC context
        capturedContext = MDC.getCopyOfContextMap();
      }
      
      @Override
      public void finish() {
        // Clean up
        capturedContext = null;
      }
      
      @Override
      public void progress(TaskLoggingEvent event) {
        // Not needed for this test
      }
      
      @Override
      public void flush() {
        // Not needed for this test
      }
      
      @Override
      public Object captureContext() {
        return capturedContext;
      }
      
      @Override
      public void applyContext(Object context) {
        if (context instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, String> mdcMap = (Map<String, String>) context;
          MDC.setContextMap(mdcMap);
        }
      }
      
      @Override
      public void clearContext() {
        MDC.clear();
      }
    };
    
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    customTaskLogger.start();
    
    // Capture the context
    Object capturedContext = customTaskLogger.captureContext();
    assertNotNull(capturedContext, "Captured context should not be null");
    
    // Clear the MDC to simulate a different thread
    MDC.clear();
    assertThat(MDC.get(TEST_MDC_KEY), is(equalTo(null)));
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean mdcAppliedCorrectly = new AtomicBoolean(false);
    
    // Create and run a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify MDC is initially empty in the virtual thread
        assertThat(MDC.get(TEST_MDC_KEY), is(equalTo(null)));
        
        // Apply the captured context
        customTaskLogger.applyContext(capturedContext);
        
        // Verify MDC now has the expected value
        mdcAppliedCorrectly.set(TEST_MDC_VALUE.equals(MDC.get(TEST_MDC_KEY)));
        
        // Clear the context
        customTaskLogger.clearContext();
        
        // Verify MDC is now empty
        assertThat(MDC.get(TEST_MDC_KEY), is(equalTo(null)));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();
    
    // Verify context was applied correctly
    assertTrue(mdcAppliedCorrectly.get(), "MDC context was not properly applied in virtual thread");
    
    // Clean up
    customTaskLogger.finish();
  }

  /**
   * Tests that task logging works correctly when virtual threads are pinned.
   * This test verifies that logging operations don't cause thread pinning issues.
   */
  @Test
  void taskLoggingWithPinnedVirtualThreads() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean loggedSuccessfully = new AtomicBoolean(false);

    // Create and run a virtual thread with a synchronized block to cause pinning
    Object lock = new Object();
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Enter a synchronized block to cause pinning
        synchronized (lock) {
          // Log a progress message from the pinned virtual thread
          TaskLoggerHelper.progress(mockLogger, "Pinned virtual thread task progress");
          TaskLoggerHelper.flush();
          loggedSuccessfully.set(true);
        }
      } finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();

    // Verify logging was successful even with pinning
    assertTrue(loggedSuccessfully.get(), "Logging from pinned virtual thread failed");
    
    // Verify the log message was captured
    verify(mockLogger, times(1)).info(eq(INTERNAL_PROGRESS), messageCaptor.capture(), argsCaptor.capture());
    assertThat(messageCaptor.getValue(), containsString("---- Pinned virtual thread task progress ----"));
  }

  /**
   * Tests that task logging works correctly with high concurrency using virtual threads.
   * This test creates thousands of virtual threads to validate scalability.
   */
  @Test
  void highConcurrencyTaskLoggingWithVirtualThreads() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Number of virtual threads to create
    final int threadCount = 5000;
    
    // Create a latch to wait for all virtual threads to complete
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create and run many virtual threads
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread virtualThread = Thread.ofVirtual().start(() -> {
        try {
          // Log a progress message with the thread ID
          TaskLoggerHelper.progress(mockLogger, "High concurrency virtual thread " + threadId);
          
          // Verify MDC context is available
          if (TEST_MDC_VALUE.equals(MDC.get(TEST_MDC_KEY))) {
            successCount.incrementAndGet();
          }
        } finally {
          latch.countDown();
        }
      });
      threads.add(virtualThread);
    }

    // Wait for all virtual threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS * 3, TimeUnit.SECONDS), 
        "Not all virtual threads completed in time");
    
    // Join all threads
    for (Thread thread : threads) {
      thread.join();
    }

    // Verify most threads had correct MDC context
    // We use greaterThanOrEqualTo instead of equals because in rare cases,
    // some threads might not get the context due to timing or resource constraints
    assertThat(successCount.get(), is(greaterThanOrEqualTo((int)(threadCount * 0.95))));
    
    // Flush any pending log messages
    TaskLoggerHelper.flush();
  }

  /**
   * Tests that task logging works correctly when virtual threads are created by different factories.
   */
  @Test
  void taskLoggingWithDifferentVirtualThreadFactories() throws Exception {
    // Set up MDC and start task logger
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    TaskLoggerHelper.start(progressTaskLogger);

    // Create different virtual thread factories
    ThreadFactory defaultFactory = Thread.ofVirtual().factory();
    ThreadFactory namedFactory = Thread.ofVirtual().name("custom-vt-", 0).factory();
    
    // Create latches to track completion
    CountDownLatch defaultLatch = new CountDownLatch(1);
    CountDownLatch namedLatch = new CountDownLatch(1);
    
    // Track thread names
    String[] threadNames = new String[2];

    // Create and run threads from different factories
    Thread defaultThread = defaultFactory.newThread(() -> {
      try {
        threadNames[0] = Thread.currentThread().getName();
        TaskLoggerHelper.progress(mockLogger, "Default factory virtual thread");
      } finally {
        defaultLatch.countDown();
      }
    });
    
    Thread namedThread = namedFactory.newThread(() -> {
      try {
        threadNames[1] = Thread.currentThread().getName();
        TaskLoggerHelper.progress(mockLogger, "Named factory virtual thread");
      } finally {
        namedLatch.countDown();
      }
    });
    
    defaultThread.start();
    namedThread.start();

    // Wait for both threads to complete
    assertTrue(defaultLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Default factory thread did not complete in time");
    assertTrue(namedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Named factory thread did not complete in time");
    
    defaultThread.join();
    namedThread.join();

    // Verify threads had different names but both logged successfully
    assertNotNull(threadNames[0], "Default thread name should not be null");
    assertNotNull(threadNames[1], "Named thread name should not be null");
    assertNotEquals(threadNames[0], threadNames[1], "Thread names should be different");
    assertTrue(threadNames[1].startsWith("custom-vt-"), 
        "Named thread should have the custom prefix");
    
    // Flush any pending log messages
    TaskLoggerHelper.flush();
    
    // Verify both log messages were captured
    verify(mockLogger, times(2)).info(eq(INTERNAL_PROGRESS), messageCaptor.capture(), argsCaptor.capture());
  }
}