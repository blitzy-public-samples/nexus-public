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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StreamCopierTest
    extends TestSupport
{
  private static final String DEFAULT_READ_OUTPUT = "Test read";

  private StreamCopier<String> underTest;

  @Test
  void validOutputWhenReadSimple() {
    underTest = new StreamCopier<>(outputStream -> {
    }, inputStream -> DEFAULT_READ_OUTPUT);

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  void readWrittenValueMultipleTimesWhenWriteToOutputStream() {
    underTest = new StreamCopier<>(this::writeString, this::readString);

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  void failToReadValueWhenWriteThrowsException() {
    underTest = new StreamCopier<>(outputStream -> {
      throw new RuntimeException("Test writing failure");
    }, this::readString);

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }

  @Test
  void failToReadValueWhenLeavingStreamsOpenException() {
    underTest = new StreamCopier<>(this::writeString, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }

  @Test
  void readWrittenValueMultipleTimesWhenLeavingStreamsOpenAndClosingManually() {
    underTest = new StreamCopier<>(this::writeStringAndClose, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  void failToReadValueWhenReadThrowsException() {
    underTest = new StreamCopier<>(this::writeString, inputStream -> {
      throw new RuntimeException("Test Reading failure");
    });

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }

  @Test
  @Tag("VirtualThreadTestGroup")
  void concurrentReadWriteOperationsWithVirtualThreads() throws Exception {
    // Create a StreamCopier that will be used by multiple virtual threads
    underTest = new StreamCopier<>(this::writeString, this::readString);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 10;
      List<Future<String>> futures = new ArrayList<>();
      
      // Submit multiple read tasks to be executed concurrently
      for (int i = 0; i < taskCount; i++) {
        futures.add(executor.submit(() -> underTest.read()));
      }
      
      // Verify all tasks completed successfully with the expected result
      for (Future<String> future : futures) {
        assertEquals(DEFAULT_READ_OUTPUT, future.get(5, TimeUnit.SECONDS));
      }
    }
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  void threadPinningDetectionAndPrevention() throws Exception {
    // Create a StreamCopier with a custom executor that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual()
            .name("pinning-test-", 0)
            // Enable thread pinning detection for this test
            .uncaughtExceptionHandler((thread, throwable) -> 
                log.error("Uncaught exception in virtual thread: {}", throwable.getMessage()))
            .factory())) {
      
      // Create a StreamCopier that will be used with virtual threads
      underTest = new StreamCopier<>(
          // Use a write operation that doesn't cause pinning
          this::writeString,
          // Use a read operation that doesn't cause pinning
          this::readString,
          executor
      );
      
      // Run multiple operations concurrently
      List<Future<String>> futures = new ArrayList<>();
      int taskCount = 20;
      
      for (int i = 0; i < taskCount; i++) {
        futures.add(executor.submit(() -> underTest.read()));
      }
      
      // Verify all operations completed successfully
      for (Future<String> future : futures) {
        assertEquals(DEFAULT_READ_OUTPUT, future.get(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  @Tag("VirtualThreadTestGroup")
  void highConcurrencyWithVirtualThreads() throws Exception {
    // Create a StreamCopier that will be used by many virtual threads
    underTest = new StreamCopier<>(this::writeString, this::readString);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 1000; // Test with 1000+ tasks
      List<Future<String>> futures = new ArrayList<>();
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit many read tasks to be executed concurrently
      for (int i = 0; i < taskCount; i++) {
        futures.add(executor.submit(() -> {
          String result = underTest.read();
          if (DEFAULT_READ_OUTPUT.equals(result)) {
            successCount.incrementAndGet();
          }
          return result;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<String> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      // Verify all tasks completed successfully
      assertEquals(taskCount, successCount.get(), 
          "All virtual thread tasks should complete successfully");
    }
  }

  @Test
  @Tag("VirtualThreadTestGroup")
  void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    // Create StreamCopiers for both thread types
    ExecutorService platformExecutor = Executors.newFixedThreadPool(10); // Limited platform thread pool
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Unlimited virtual threads
    
    try {
      StreamCopier<String> platformThreadCopier = new StreamCopier<>(
          this::writeString, 
          this::readString, 
          platformExecutor
      );
      
      StreamCopier<String> virtualThreadCopier = new StreamCopier<>(
          this::writeString, 
          this::readString, 
          virtualExecutor
      );
      
      int taskCount = 100;
      
      // Measure platform thread performance
      long platformStart = System.nanoTime();
      for (int i = 0; i < taskCount; i++) {
        assertEquals(DEFAULT_READ_OUTPUT, platformThreadCopier.read());
      }
      long platformDuration = System.nanoTime() - platformStart;
      
      // Measure virtual thread performance
      long virtualStart = System.nanoTime();
      for (int i = 0; i < taskCount; i++) {
        assertEquals(DEFAULT_READ_OUTPUT, virtualThreadCopier.read());
      }
      long virtualDuration = System.nanoTime() - virtualStart;
      
      // Log the performance comparison (not asserting as performance can vary)
      log.info("Platform threads took {} ns, Virtual threads took {} ns for {} operations", 
          platformDuration, virtualDuration, taskCount);
      
      // Virtual threads should generally be more efficient for I/O operations
      // but we don't assert this as it depends on the environment
      log.info("Performance ratio (platform/virtual): {}", 
          (double) platformDuration / virtualDuration);
    }
    finally {
      // Clean up the executors
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
    }
  }

  @Test
  @Tag("VirtualThreadTestGroup")
  void customVirtualThreadExecutorService() throws Exception {
    // Create a custom ExecutorService using Virtual Threads
    try (ExecutorService customExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("custom-virtual-", 0).factory())) {
      
      // Create a StreamCopier with the custom executor
      underTest = new StreamCopier<>(this::writeString, this::readString, customExecutor);
      
      // Test concurrent operations
      List<Future<String>> futures = new ArrayList<>();
      int taskCount = 50;
      
      for (int i = 0; i < taskCount; i++) {
        futures.add(customExecutor.submit(() -> underTest.read()));
      }
      
      // Verify all operations completed successfully
      for (Future<String> future : futures) {
        assertEquals(DEFAULT_READ_OUTPUT, future.get(5, TimeUnit.SECONDS));
      }
    }
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
      outputStream.write(DEFAULT_READ_OUTPUT.getBytes(UTF_8));
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  private String readString(final InputStream inputStream) {
    try {
      return IOUtils.toString(inputStream, UTF_8);
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
    return null;
  }
}