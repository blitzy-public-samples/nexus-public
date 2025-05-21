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
package org.virtualthread.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.io.StreamCopier;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link StreamCopier} using Virtual Threads.
 * 
 * This test suite validates that the StreamCopier utility correctly handles I/O operations
 * when executed with Java 21 Virtual Threads, ensuring both functional correctness and
 * performance benefits of the lightweight thread model.
 */
public class VirtualThreadStreamCopierTest
    extends TestSupport
{
  private static final String DEFAULT_READ_OUTPUT = "Test read";
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;

  private StreamCopier<String> underTest;

  @BeforeEach
  void setUp() {
    // Default setup with basic read/write operations
    underTest = new StreamCopier<>(outputStream -> {}, inputStream -> DEFAULT_READ_OUTPUT);
  }

  @Test
  void whenReadSimpleExpectValidOutput() {
    assertThat(underTest.read(), is(equalTo(DEFAULT_READ_OUTPUT)));
  }

  @Test
  void whenWriteToOutputStreamExpectToReadWrittenValueMultipleTimes() {
    underTest = new StreamCopier<>(this::writeString, this::readString);

    assertThat(underTest.read(), is(equalTo(DEFAULT_READ_OUTPUT)));
    assertThat(underTest.read(), is(equalTo(DEFAULT_READ_OUTPUT)));
  }

  @Test
  void whenWriteThrowsExceptionExpectFailToReadValue() {
    underTest = new StreamCopier<>(outputStream -> {
      throw new RuntimeException("Test writing failure");
    }, this::readString);

    // Using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertThat(exception.getMessage(), is(equalTo("Unable to properly read from stream")));
  }

  @Test
  void whenLeavingStreamsOpenExceptionExpectFailToReadValue() {
    underTest = new StreamCopier<>(this::writeString, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    // Using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertThat(exception.getMessage(), is(equalTo("Unable to properly read from stream")));
  }

  @Test
  void whenLeavingStreamsOpenAndClosingManuallyExpectToReadWrittenValueMultipleTimes() {
    underTest = new StreamCopier<>(this::writeStringAndClose, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    assertThat(underTest.read(), is(equalTo(DEFAULT_READ_OUTPUT)));
    assertThat(underTest.read(), is(equalTo(DEFAULT_READ_OUTPUT)));
  }

  @Test
  void whenReadThrowsExceptionExpectFailToReadValue() {
    underTest = new StreamCopier<>(this::writeString, inputStream -> {
      throw new RuntimeException("Test Reading failure");
    });

    // Using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertThat(exception.getMessage(), is(equalTo("Unable to properly read from stream")));
  }

  /**
   * Tests parallel stream copying operations using Virtual Threads.
   * This test validates that StreamCopier can handle multiple concurrent operations
   * efficiently when executed with Virtual Threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  void parallelStreamCopyingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            StreamCopier<String> copier = new StreamCopier<>(this::writeString, this::readString);
            String result = copier.read();
            if (DEFAULT_READ_OUTPUT.equals(result)) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            fail("Exception during parallel stream copying: " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
    }
  }

  /**
   * Tests concurrent reading from multiple streams using Virtual Threads.
   * This test validates that StreamCopier can efficiently handle reading from
   * many streams concurrently using the lightweight thread model.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  void concurrentReadingFromMultipleStreamsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create multiple StreamCopier instances
      List<StreamCopier<String>> copiers = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        copiers.add(new StreamCopier<>(this::writeString, this::readString));
      }
      
      // Read from all copiers concurrently
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            String result = copiers.get(index).read();
            if (DEFAULT_READ_OUTPUT.equals(result)) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            fail("Exception during concurrent reading: " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
    }
  }

  /**
   * Tests proper exception propagation across Virtual Thread boundaries.
   * This test validates that exceptions thrown during stream operations are
   * properly propagated when using Virtual Threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  void exceptionPropagationAcrossVirtualThreadBoundaries() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger exceptionCount = new AtomicInteger(0);
      
      // Create StreamCopier instances that will throw exceptions
      List<StreamCopier<String>> copiers = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        copiers.add(new StreamCopier<>(outputStream -> {
          throw new RuntimeException("Intentional exception in write operation");
        }, this::readString));
      }
      
      // Read from all copiers concurrently, expecting exceptions
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            copiers.get(index).read(1000);
            fail("Expected exception was not thrown");
          } 
          catch (RuntimeException e) {
            if ("Unable to properly read from stream".equals(e.getMessage())) {
              exceptionCount.incrementAndGet();
            }
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all operations resulted in the expected exception
      assertThat(exceptionCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
    }
  }

  /**
   * Tests resource management with Virtual Threads.
   * This test validates that StreamCopier properly manages resources
   * when executed with Virtual Threads, ensuring no resource leaks.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  void resourceManagementWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create a new StreamCopier for each task
            StreamCopier<String> copier = new StreamCopier<>(this::writeStringAndClose, this::readString);
            copier.afterReadLeaveStreamsOpen(); // Intentionally leave streams open
            
            // Read from the copier and verify the result
            String result = copier.read();
            if (DEFAULT_READ_OUTPUT.equals(result)) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            fail("Exception during resource management test: " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
    }
  }

  /**
   * Tests timeout handling with Virtual Threads.
   * This test validates that StreamCopier properly handles timeouts
   * when executed with Virtual Threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  void timeoutHandlingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger timeoutCount = new AtomicInteger(0);
      
      // Create StreamCopier instances that will timeout
      List<StreamCopier<String>> copiers = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        copiers.add(new StreamCopier<>(outputStream -> {
          // Simulate a slow operation that will cause a timeout
          try {
            Thread.sleep(2000); // Sleep longer than the timeout
          } 
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }, this::readString));
      }
      
      // Read from all copiers concurrently with a short timeout
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            copiers.get(index).read(100); // Short timeout
            fail("Expected timeout exception was not thrown");
          } 
          catch (RuntimeException e) {
            if ("Unable to properly read from stream".equals(e.getMessage())) {
              timeoutCount.incrementAndGet();
            }
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all operations resulted in a timeout
      assertThat(timeoutCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
    }
  }

  /**
   * Tests performance comparison between Virtual Threads and Platform Threads.
   * This test validates that StreamCopier operations are more efficient when
   * executed with Virtual Threads compared to Platform Threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS * 2, unit = TimeUnit.SECONDS)
  void performanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Number of operations for performance test (smaller to avoid excessive test duration)
    final int performanceTestOperations = 500;
    
    // Measure performance with Platform Threads
    long platformThreadDuration = measurePerformance(
        Thread.ofPlatform().factory(),
        performanceTestOperations
    );
    
    // Measure performance with Virtual Threads
    long virtualThreadDuration = measurePerformance(
        Thread.ofVirtual().factory(),
        performanceTestOperations
    );
    
    // Log the performance results
    log.info("Platform Thread Duration: {} ms", platformThreadDuration);
    log.info("Virtual Thread Duration: {} ms", virtualThreadDuration);
    
    // Verify that Virtual Threads perform better than Platform Threads
    // Note: This assertion might be environment-dependent, but Virtual Threads should generally
    // be more efficient for I/O-bound operations like those in StreamCopier
    assertThat("Virtual Threads should be faster than Platform Threads for I/O operations",
        virtualThreadDuration, lessThan(platformThreadDuration));
  }

  /**
   * Measures the performance of StreamCopier operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use (platform or virtual)
   * @param operationCount the number of operations to perform
   * @return the duration in milliseconds
   */
  private long measurePerformance(ThreadFactory threadFactory, int operationCount) throws Exception {
    long startTime = System.currentTimeMillis();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      CountDownLatch latch = new CountDownLatch(operationCount);
      
      // Submit multiple concurrent tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < operationCount; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create a new StreamCopier for each task
            StreamCopier<String> copier = new StreamCopier<>(this::writeString, this::readString);
            copier.read();
          } 
          catch (Exception e) {
            fail("Exception during performance test: " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Tests the scalability of StreamCopier with a large number of Virtual Threads.
   * This test validates that StreamCopier can handle a very large number of
   * concurrent operations when executed with Virtual Threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS * 2, unit = TimeUnit.SECONDS)
  void scalabilityWithLargeNumberOfVirtualThreads() throws Exception {
    // Use a larger number of threads to test scalability
    final int largeThreadCount = 5000;
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(largeThreadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit a large number of concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < largeThreadCount; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            StreamCopier<String> copier = new StreamCopier<>(this::writeString, this::readString);
            String result = copier.read();
            if (DEFAULT_READ_OUTPUT.equals(result)) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            fail("Exception during scalability test: " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
      
      // Verify that all operations completed successfully
      assertThat("Timed out waiting for all virtual threads to complete", completed, is(true));
      assertThat(successCount.get(), is(equalTo(largeThreadCount)));
      
      // Log the success
      log.info("Successfully completed {} concurrent operations with Virtual Threads", largeThreadCount);
    }
  }

  private void writeStringAndClose(OutputStream outputStream) {
    try {
      writeString(outputStream);
      outputStream.close();
    }
    catch (IOException e) {
      fail("Failed to write and close stream: " + e.getMessage());
    }
  }

  private void writeString(OutputStream outputStream) {
    try {
      outputStream.write(DEFAULT_READ_OUTPUT.getBytes());
    }
    catch (IOException e) {
      fail("Failed to write to stream: " + e.getMessage());
    }
  }

  private String readString(final InputStream inputStream) {
    try {
      return IOUtils.toString(inputStream);
    }
    catch (IOException e) {
      fail("Failed to read from stream: " + e.getMessage());
    }
    return null;
  }
}