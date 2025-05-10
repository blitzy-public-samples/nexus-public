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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.sonatype.nexus.common.thread.VirtualThreadTestGroup;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Special test to cover MDC thread specifics. See NEXUS-14432 and https://logback.qos.ch/manual/mdc.html#managedThreads
 * (tldr: logback with MDC and thread pools doesn't copy the MDC values so we have to do it manually)
 */
@ExtendWith(MockitoExtension.class)
public class ProgressTaskLoggerMDCTest
{
  @Mock
  private Logger mockLogger;

  @Test
  public void testMDCCopy() throws InterruptedException {
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

  /**
   * Test MDC context propagation in virtual threads.
   */
  @Test
  @Tag("virtual-thread")
  public void testMDCCopyWithVirtualThreads() throws InterruptedException {
    String mainThread = Thread.currentThread().getName();

    // put something into MDC in current thread
    MDC.put("foo", "bar");

    // since we are testing with threads, we need to track that the inner thread ran through
    AtomicBoolean tested = new AtomicBoolean(false);

    // create progress task logger with 1ms start delay for virtual thread testing
    // Note: ProgressTaskLogger internally uses virtual threads for logging operations
    ProgressTaskLogger progressTaskLogger = new ProgressTaskLogger(
        mockLogger, 1, 60000, TimeUnit.MILLISECONDS)
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

  /**
   * Test high concurrency MDC propagation with platform threads.
   */
  @Test
  public void testMDCCopyUnderLoad() throws InterruptedException {
    final int THREAD_COUNT = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicBoolean allThreadsCorrect = new AtomicBoolean(true);

    // put something into MDC in current thread
    MDC.put("foo", "bar");

    // Create multiple progress task loggers to simulate high concurrency
    for (int i = 0; i < THREAD_COUNT; i++) {
      final String threadId = "thread-" + i;
      ProgressTaskLogger progressTaskLogger = new ProgressTaskLogger(mockLogger, 1, 60000, TimeUnit.MILLISECONDS)
      {
        void logProgress() {
          super.logProgress();
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Verify MDC context is correctly propagated
            if (!"bar".equals(MDC.get("foo"))) {
              allThreadsCorrect.set(false);
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            completionLatch.countDown();
          }
        }
      };

      progressTaskLogger.progress(new TaskLoggingEvent(mockLogger, "test message from " + threadId));
      progressTaskLogger.start();
    }

    // Release all threads to execute concurrently
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(1, TimeUnit.SECONDS), "Not all threads completed in time");
    
    // Verify all threads had correct MDC context
    assertTrue(allThreadsCorrect.get(), "MDC context was not correctly propagated in all threads");
  }

  /**
   * Test high concurrency MDC propagation with virtual threads.
   */
  @Test
  @Tag("virtual-thread")
  public void testMDCCopyUnderLoadWithVirtualThreads() throws InterruptedException {
    final int THREAD_COUNT = 100; // Higher count for virtual threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicBoolean allThreadsCorrect = new AtomicBoolean(true);

    // put something into MDC in current thread
    MDC.put("foo", "bar");

    // Create multiple progress task loggers to simulate high concurrency with virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      final String threadId = "vthread-" + i;
      // Create a progress task logger for virtual thread testing
      // Note: ProgressTaskLogger internally uses virtual threads for logging operations
      ProgressTaskLogger progressTaskLogger = new ProgressTaskLogger(
          mockLogger, 1, 60000, TimeUnit.MILLISECONDS)
      {
        void logProgress() {
          super.logProgress();
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Verify MDC context is correctly propagated
            if (!"bar".equals(MDC.get("foo"))) {
              allThreadsCorrect.set(false);
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            completionLatch.countDown();
          }
        }
      };

      progressTaskLogger.progress(new TaskLoggingEvent(mockLogger, "test message from " + threadId));
      progressTaskLogger.start();
    }

    // Release all threads to execute concurrently
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(1, TimeUnit.SECONDS), "Not all threads completed in time");
    
    // Verify all threads had correct MDC context
    assertTrue(allThreadsCorrect.get(), "MDC context was not correctly propagated in all virtual threads");
  }
}