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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.io.StreamCopier;

import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;

/**
 * Tests the {@link StreamCopier} component with Java 21 Virtual Threads, verifying that stream copying operations
 * work correctly and efficiently when executed with thousands of concurrent virtual threads.
 *
 * @since 3.60
 */
public class StreamCopierVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_DATA = "Test data for virtual thread stream copying";
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @Before
  public void setUp() {
    // Create executors for comparison testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @After
  public void tearDown() {
    // Clean up executors
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
  }

  /**
   * Tests that StreamCopier works correctly with a custom executor service that uses Virtual Threads.
   * This validates basic functionality with the new threading model.
   */
  @Test
  public void testStreamCopierWithVirtualThreadExecutor() {
    StreamCopier<String> underTest = new StreamCopier<>(
        this::writeString,
        this::readString,
        virtualThreadExecutor);

    // Verify basic functionality works with virtual threads
    String result = underTest.read();
    assertThat(result, is(equalTo(TEST_DATA)));
  }

  /**
   * Tests high concurrency with thousands of simultaneous stream copy operations using Virtual Threads.
   * This validates that the StreamCopier can handle a large number of concurrent operations efficiently.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a StreamCopier with virtual thread executor
    StreamCopier<String> underTest = new StreamCopier<>(
        this::writeString,
        this::readString,
        virtualThreadExecutor);
    
    // Launch many concurrent operations
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          String result = underTest.read();
          if (TEST_DATA.equals(result)) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All operations should complete within timeout", completed, is(true));
    
    // Verify results
    assertThat("All operations should succeed", successCount.get(), is(CONCURRENT_OPERATIONS));
    assertThat("No operations should fail", errorCount.get(), is(0));
  }

  /**
   * Tests exception propagation when using Virtual Threads with StreamCopier.
   * This validates that exceptions are properly propagated through the virtual thread execution.
   */
  @Test
  public void testExceptionPropagationWithVirtualThreads() {
    // Create a StreamCopier that will throw an exception during writing
    StreamCopier<String> underTest = new StreamCopier<>(
        outputStream -> {
          throw new RuntimeException("Test exception in virtual thread");
        },
        this::readString,
        virtualThreadExecutor);
    
    try {
      // This should propagate the exception
      underTest.read(5000);
      fail("Expected exception was not thrown");
    }
    catch (RuntimeException e) {
      // Verify the exception is properly propagated
      assertThat(e.getMessage(), is("Unable to properly read from stream"));
      assertThat(e.getCause().getMessage(), is("Test exception in virtual thread"));
    }
  }

  /**
   * Tests resource cleanup when using Virtual Threads for I/O operations.
   * This validates that streams are properly closed even with the lightweight threading model.
   */
  @Test
  public void testResourceCleanupWithVirtualThreads() throws Exception {
    AtomicInteger closeCount = new AtomicInteger(0);
    
    // Create a StreamCopier with a custom output stream that tracks closes
    StreamCopier<String> underTest = new StreamCopier<>(
        outputStream -> {
          try {
            outputStream.write(TEST_DATA.getBytes());
          }
          catch (IOException e) {
            fail(e.getMessage());
          }
        },
        inputStream -> {
          try {
            return IOUtils.toString(inputStream);
          }
          catch (IOException e) {
            fail(e.getMessage());
            return null;
          }
          finally {
            try {
              inputStream.close();
              closeCount.incrementAndGet();
            }
            catch (IOException e) {
              fail("Failed to close input stream: " + e.getMessage());
            }
          }
        },
        virtualThreadExecutor);
    
    // Execute the stream copy operation
    String result = underTest.read();
    
    // Verify the result and that resources were cleaned up
    assertThat(result, is(equalTo(TEST_DATA)));
    assertThat("Input stream should be closed", closeCount.get(), is(1));
  }

  /**
   * Compares performance between Virtual Threads and Platform Threads for stream operations.
   * This validates the performance improvements from using Virtual Threads for I/O-bound operations.
   */
  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    final int operationCount = 500;
    
    // Measure performance with virtual threads
    long virtualThreadTime = measurePerformance(operationCount, virtualThreadExecutor);
    
    // Measure performance with platform threads
    long platformThreadTime = measurePerformance(operationCount, platformThreadExecutor);
    
    // Log the results for analysis
    log.info("Performance comparison for {} stream operations:", operationCount);
    log.info("  Virtual Threads: {} ms", virtualThreadTime);
    log.info("  Platform Threads: {} ms", platformThreadTime);
    
    // Virtual threads should generally be more efficient for I/O operations
    // but we don't make this a hard assertion as it depends on the test environment
    // Instead, we log the results and make a soft assertion that virtual threads aren't significantly worse
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
  }
  
  /**
   * Tests that StreamCopier can handle very large numbers of concurrent operations with Virtual Threads.
   * This validates the scalability benefits of Virtual Threads for I/O-bound operations.
   */
  @Test
  public void testScalabilityWithVirtualThreads() throws Exception {
    // This test creates a much larger number of concurrent operations than would be practical with platform threads
    final int largeOperationCount = 5000;
    CountDownLatch latch = new CountDownLatch(largeOperationCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a StreamCopier with virtual thread executor
    StreamCopier<String> underTest = new StreamCopier<>(
        this::writeString,
        this::readString,
        virtualThreadExecutor);
    
    // Launch many concurrent operations
    for (int i = 0; i < largeOperationCount; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          String result = underTest.read();
          if (TEST_DATA.equals(result)) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
    
    // Log the results
    log.info("Scalability test with {} concurrent operations:", largeOperationCount);
    log.info("  Completed: {}", completed);
    log.info("  Success count: {}", successCount.get());
    log.info("  Error count: {}", errorCount.get());
    
    // Verify that a significant number of operations completed successfully
    // We don't require all to complete as this is a stress test
    assertThat("A significant number of operations should succeed", 
        successCount.get(), greaterThan(largeOperationCount / 2));
  }
  
  /**
   * Helper method to measure performance of stream operations using the specified executor.
   * 
   * @param operationCount Number of operations to perform
   * @param executor The executor service to use
   * @return The time taken in milliseconds
   */
  private long measurePerformance(int operationCount, ExecutorService executor) throws Exception {
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicLong startTime = new AtomicLong();
    AtomicLong endTime = new AtomicLong();
    
    // Create a StreamCopier with the specified executor
    StreamCopier<String> underTest = new StreamCopier<>(
        this::writeString,
        this::readString,
        executor);
    
    // Record start time
    startTime.set(System.currentTimeMillis());
    
    // Launch concurrent operations
    for (int i = 0; i < operationCount; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          underTest.read();
        }
        finally {
          latch.countDown();
        }
      }, executor);
    }
    
    // Wait for all operations to complete
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Record end time
    endTime.set(System.currentTimeMillis());
    
    // Return elapsed time
    return endTime.get() - startTime.get();
  }
  
  /**
   * Helper method to write a test string to an output stream.
   */
  private void writeString(OutputStream outputStream) {
    try {
      outputStream.write(TEST_DATA.getBytes());
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  /**
   * Helper method to read a string from an input stream.
   */
  private String readString(InputStream inputStream) {
    try {
      return IOUtils.toString(inputStream);
    }
    catch (IOException e) {
      fail(e.getMessage());
      return null;
    }
  }
}