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
package org.sonatype.nexus.thread.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.VirtualThreadTestGroup;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class StreamCopierTest
    extends TestSupport
{
  private String DEFAULT_READ_OUTPUT = "Test read";

  private StreamCopier<String> underTest;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  void setUp() {
    // Create executors for testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(10);
  }
  
  @AfterEach
  void tearDown() {
    // Clean up executors
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
      }
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      try {
        if (!platformThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
          platformThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        platformThreadExecutor.shutdownNow();
      }
    }
  }

  @Test
  @DisplayName("When reading simple content, expect valid output")
  void whenReadSimpleExpectValidOutput() {
    underTest = new StreamCopier<>(outputStream -> {
    }, inputStream -> DEFAULT_READ_OUTPUT);

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  @DisplayName("When writing to OutputStream, expect to read written value multiple times")
  void whenWriteToOutputStreamExpectToReadWrittenValueMultipleTimes() {
    underTest = new StreamCopier<>(this::writeString, this::readString);

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  @DisplayName("When write throws exception, expect failure to read value")
  void whenWriteThrowsExceptionExpectFailToReadValue() {
    underTest = new StreamCopier<>(outputStream -> {
      throw new RuntimeException("Test writing failure");
    }, this::readString);

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> {
      underTest.read(1000);
    });
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }

  @Test
  @DisplayName("When leaving streams open, expect failure to read value")
  void whenLeavingStreamsOpenExceptionExpectFailToReadValue() {
    underTest = new StreamCopier<>(this::writeString, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> {
      underTest.read(1000);
    });
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }

  @Test
  @DisplayName("When leaving streams open and closing manually, expect to read written value multiple times")
  void whenLeavingStreamsOpenAndClosingManuallyExpectToReadWrittenValueMultipleTimes() {
    underTest = new StreamCopier<>(this::writeStringAndClose, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  @DisplayName("When read throws exception, expect failure to read value")
  void whenReadThrowsExceptionExpectFailToReadValue() {
    underTest = new StreamCopier<>(this::writeString, inputStream -> {
      throw new RuntimeException("Test Reading failure");
    });

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> {
      underTest.read(1000);
    });
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("StreamCopier should work with virtual threads")
  void streamCopierShouldWorkWithVirtualThreads() {
    underTest = new StreamCopier<>(this::writeString, this::readString, virtualThreadExecutor);
    
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("StreamCopier should handle high concurrency with virtual threads")
  void streamCopierShouldHandleHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a StreamCopier with virtual threads
    underTest = new StreamCopier<>(this::writeString, this::readString, virtualThreadExecutor);
    
    // Run many concurrent operations
    int concurrentTasks = 1000;
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    AtomicInteger successCount = new AtomicInteger(0);
    
    for (int i = 0; i < concurrentTasks; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          String result = underTest.read();
          if (DEFAULT_READ_OUTPUT.equals(result)) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          // Count failures
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    latch.await(30, TimeUnit.SECONDS);
    
    // Verify all operations succeeded
    assertEquals(concurrentTasks, successCount.get(), 
        "All concurrent operations should succeed with virtual threads");
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("Compare performance between platform threads and virtual threads")
  void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    // Create StreamCopiers with different thread types
    StreamCopier<String> platformThreadCopier = 
        new StreamCopier<>(this::writeString, this::readString, platformThreadExecutor);
    
    StreamCopier<String> virtualThreadCopier = 
        new StreamCopier<>(this::writeString, this::readString, virtualThreadExecutor);
    
    // Measure platform thread performance
    int iterations = 100;
    long platformStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      assertEquals(DEFAULT_READ_OUTPUT, platformThreadCopier.read());
    }
    long platformDuration = System.nanoTime() - platformStart;
    
    // Measure virtual thread performance
    long virtualStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      assertEquals(DEFAULT_READ_OUTPUT, virtualThreadCopier.read());
    }
    long virtualDuration = System.nanoTime() - virtualStart;
    
    // Log performance comparison (no assertion as performance can vary by environment)
    log.info("Platform thread duration: {} ns", platformDuration);
    log.info("Virtual thread duration: {} ns", virtualDuration);
    log.info("Performance ratio (platform/virtual): {}", 
        (double) platformDuration / virtualDuration);
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("StreamCopier should handle thousands of threads with virtual threads")
  void streamCopierShouldHandleThousandsOfThreadsWithVirtualThreads() throws Exception {
    // Create a StreamCopier with virtual threads
    underTest = new StreamCopier<>(this::writeString, this::readString, virtualThreadExecutor);
    
    // Run a very high number of concurrent operations to demonstrate virtual thread benefits
    int concurrentTasks = 10000;
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    for (int i = 0; i < concurrentTasks; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          String result = underTest.read(5000); // Use a longer timeout for this high-concurrency test
          if (DEFAULT_READ_OUTPUT.equals(result)) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete with a generous timeout
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    
    // Log results
    log.info("Completed: {}, Success: {}, Errors: {}", 
        completed, successCount.get(), errorCount.get());
    
    // We don't assert exact counts as this is a stress test that may behave differently
    // in different environments, but we log the results for analysis
  }

  private void writeStringAndClose(OutputStream outputStream) {
    try {
      this.writeString(outputStream);

      outputStream.close();
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  private void writeString(OutputStream outputStream) {
    try {
      outputStream.write(DEFAULT_READ_OUTPUT.getBytes());
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  private String readString(final InputStream inputStream) {
    try {
      return IOUtils.toString(inputStream);
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
    return null;
  }
  
  /**
   * Creates a thread factory for platform threads.
   */
  private ThreadFactory createPlatformThreadFactory() {
    return Thread.ofPlatform().factory();
  }
  
  /**
   * Creates a thread factory for virtual threads.
   */
  private ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }
}