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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.MDC;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Special test to cover MDC thread specifics. See NEXUS-14432 and https://logback.qos.ch/manual/mdc.html#managedThreads
 * (tldr: logback with MDC and thread pools doesn't copy the MDC values so we have to do it manually)
 * 
 * This test also verifies MDC context propagation in both platform threads and virtual threads (Java 21+).
 */
@ExtendWith(MockitoExtension.class)
@VirtualThreadTestGroup
public class ProgressTaskLoggerMDCTest
{
  @Mock
  private Logger mockLogger;

  @BeforeEach
  void setUp() {
    // Clear any MDC values that might be left from previous tests
    MDC.clear();
  }

  @Test
  void testMDCCopyWithPlatformThread() throws InterruptedException {
    String mainThread = Thread.currentThread().getName();

    // put something into MDC in current thread
    MDC.put("foo", "bar");

    // since we are testing with threads, we need to track that the inner thread ran through
    AtomicBoolean tested = new AtomicBoolean(false);

    // create progress task logger with 1ms start delay (i.e. start immediately). Interval is not relevant for this
    // test.
    ProgressTaskLogger progressTaskLogger = new ProgressTaskLogger(mockLogger, 1, 60000, TimeUnit.MILLISECONDS)
    {
      void logProgress() {
        super.logProgress();
        assertThat(mainThread, not(equalTo(Thread.currentThread().getName()))); // verify we are in a different thread
        assertThat(MDC.get("foo"), equalTo("bar"));
        tested.set(true);
      }
    };

    // store a progress message before start so it is there immediately
    progressTaskLogger.progress(new TaskLoggingEvent(mockLogger, "test message"));

    progressTaskLogger.start();

    Thread.sleep(100); // wait for the execution to complete

    // assert that the test ran
    assertTrue(tested.get());

    progressTaskLogger.finish();
  }

  @Test
  void testMDCCopyWithVirtualThread() throws InterruptedException {
    String mainThread = Thread.currentThread().getName();

    // put something into MDC in current thread
    MDC.put("foo", "bar");

    // since we are testing with threads, we need to track that the inner thread ran through
    AtomicBoolean tested = new AtomicBoolean(false);

    // create progress task logger with virtual thread factory
    ProgressTaskLogger progressTaskLogger = new ProgressTaskLogger(mockLogger, 1, 60000, TimeUnit.MILLISECONDS, 
        Thread.ofVirtual().factory())
    {
      void logProgress() {
        super.logProgress();
        assertThat(mainThread, not(equalTo(Thread.currentThread().getName()))); // verify we are in a different thread
        assertThat(Thread.currentThread().isVirtual(), equalTo(true)); // verify we are in a virtual thread
        assertThat(MDC.get("foo"), equalTo("bar"));
        tested.set(true);
      }
    };

    // store a progress message before start so it is there immediately
    progressTaskLogger.progress(new TaskLoggingEvent(mockLogger, "test message"));

    progressTaskLogger.start();

    Thread.sleep(100); // wait for the execution to complete

    // assert that the test ran
    assertTrue(tested.get());

    progressTaskLogger.finish();
  }

  @Test
  void testHighConcurrencyMDCPropagationWithVirtualThreads() throws InterruptedException {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service with virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Number of concurrent tasks to run
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Set MDC value in the main thread
    String mdcKey = "requestId";
    String mdcValue = "main-thread-request";
    MDC.put(mdcKey, mdcValue);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Verify MDC context is propagated to the virtual thread
            String propagatedValue = MDC.get(mdcKey);
            if (!mdcValue.equals(propagatedValue)) {
              errorCount.incrementAndGet();
            }
            
            // Set a task-specific MDC value
            MDC.put("taskId", String.valueOf(taskId));
            
            // Simulate some work
            Thread.sleep(10);
            
            // Verify the task-specific MDC value is still available
            String taskSpecificValue = MDC.get("taskId");
            if (!String.valueOf(taskId).equals(taskSpecificValue)) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            // Clean up task-specific MDC values
            MDC.remove("taskId");
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat(errorCount.get(), equalTo(0));
    } finally {
      executor.shutdown();
      MDC.remove(mdcKey);
    }
  }
}