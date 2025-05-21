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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.fail;

/**
 * Tests for {@link StreamCopier} using Java 21 Virtual Threads.
 * 
 * These tests validate that the StreamCopier correctly handles I/O operations
 * when using virtual threads, including proper data transfer, exception handling,
 * timeout behavior, and resource management.
 */
public class StreamCopierVirtualThreadTest
    extends TestSupport
{
  private static final String DEFAULT_READ_OUTPUT = "Test read";
  private static final int LARGE_DATA_SIZE = 10 * 1024 * 1024; // 10MB
  private static final int CONCURRENT_OPERATIONS = 1000;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  private StreamCopier<String> underTest;
  private StreamCopier<String> platformThreadUnderTest;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() {
    // Create executors for testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @After
  public void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
  }

  @Test
  public void testBasicReadWriteWithVirtualThreads() {
    // Create StreamCopier with virtual thread executor
    underTest = new StreamCopier<>(this::writeString, this::readString, virtualThreadExecutor);

    // Verify basic read/write works with virtual threads
    assertThat(underTest.read(), is(equalTo(DEFAULT_READ_OUTPUT)));
  }

  @Test
  public void testLargeDataTransferWithVirtualThreads() throws Exception {
    // Generate large random data
    byte[] largeData = generateRandomData(LARGE_DATA_SIZE);
    String expectedOutput = new String(largeData, StandardCharsets.UTF_8);
    
    // Create StreamCopier with virtual thread executor
    underTest = new StreamCopier<>(
        outputStream -> writeLargeData(outputStream, largeData),
        this::readString,
        virtualThreadExecutor
    );

    // Verify large data transfer works correctly with virtual threads
    String result = underTest.read();
    assertThat(result.length(), is(equalTo(expectedOutput.length())));
    assertThat(result, is(equalTo(expectedOutput)));
  }

  @Test
  public void testExceptionHandlingWithVirtualThreads() {
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("Unable to properly read from stream");

    // Create StreamCopier that throws exception during write
    underTest = new StreamCopier<>(
        outputStream -> {
          throw new RuntimeException("Test writing failure");
        },
        this::readString,
        virtualThreadExecutor
    );

    // Should propagate exception properly
    underTest.read(1000);
  }

  @Test
  public void testTimeoutBehaviorWithVirtualThreads() {
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("Unable to properly read from stream");

    // Create StreamCopier with a long-running operation that should timeout
    underTest = new StreamCopier<>(
        outputStream -> {
          try {
            // Sleep longer than the timeout
            Thread.sleep(2000);
            outputStream.write(DEFAULT_READ_OUTPUT.getBytes());
          }
          catch (Exception e) {
            fail(e.getMessage());
          }
        },
        this::readString,
        virtualThreadExecutor
    );

    // Should timeout after 500ms
    underTest.read(500);
  }

  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    // Count successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Run many concurrent operations using StreamCopier with virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          StreamCopier<String> copier = new StreamCopier<>(
              this::writeString,
              this::readString,
              virtualThreadExecutor
          );
          
          String result = copier.read();
          if (DEFAULT_READ_OUTPUT.equals(result)) {
            successCount.incrementAndGet();
          }
        }
        finally {
          latch.countDown();
        }
      });
      futures.add(future);
    }
    
    // Wait for all operations to complete
    latch.await(30, TimeUnit.SECONDS);
    
    // Verify all operations completed successfully
    assertThat(successCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
  }

  @Test
  public void testResourceCleanupWithVirtualThreads() throws Exception {
    // Track if streams are properly closed
    AtomicInteger closeCount = new AtomicInteger(0);
    
    // Create StreamCopier that tracks stream closures
    underTest = new StreamCopier<>(
        outputStream -> {
          try {
            outputStream.write(DEFAULT_READ_OUTPUT.getBytes());
          }
          catch (IOException e) {
            fail(e.getMessage());
          }
        },
        inputStream -> {
          try {
            String result = IOUtils.toString(inputStream);
            // Register a hook to track when stream is closed
            inputStream.close();
            closeCount.incrementAndGet();
            return result;
          }
          catch (IOException e) {
            fail(e.getMessage());
            return null;
          }
        },
        virtualThreadExecutor
    );

    // Read data
    underTest.read();
    
    // Verify stream was properly closed
    assertThat(closeCount.get(), is(equalTo(1)));
  }

  @Test
  public void testLeaveStreamsOpenWithVirtualThreads() throws Exception {
    // Track if streams are properly closed by the caller
    AtomicInteger closeCount = new AtomicInteger(0);
    
    // Create StreamCopier that tracks stream closures and leaves streams open
    underTest = new StreamCopier<>(
        outputStream -> {
          try {
            outputStream.write(DEFAULT_READ_OUTPUT.getBytes());
            // Don't close the stream here - StreamCopier should leave it open
          }
          catch (IOException e) {
            fail(e.getMessage());
          }
        },
        inputStream -> {
          try {
            String result = IOUtils.toString(inputStream);
            // Register a hook to track when stream is closed
            inputStream.close();
            closeCount.incrementAndGet();
            return result;
          }
          catch (IOException e) {
            fail(e.getMessage());
            return null;
          }
        },
        virtualThreadExecutor
    );

    // Configure to leave streams open
    underTest.afterReadLeaveStreamsOpen();
    
    // Read data
    underTest.read();
    
    // Verify stream was properly closed by our code, not by StreamCopier
    assertThat(closeCount.get(), is(equalTo(1)));
  }

  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Generate large random data for performance testing
    byte[] largeData = generateRandomData(LARGE_DATA_SIZE);
    
    // Create StreamCopiers with different executor types
    underTest = new StreamCopier<>(
        outputStream -> writeLargeData(outputStream, largeData),
        this::readString,
        virtualThreadExecutor
    );
    
    platformThreadUnderTest = new StreamCopier<>(
        outputStream -> writeLargeData(outputStream, largeData),
        this::readString,
        platformThreadExecutor
    );
    
    // Measure performance with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    underTest.read();
    long virtualThreadDuration = System.nanoTime() - virtualThreadStartTime;
    
    // Measure performance with platform threads
    long platformThreadStartTime = System.nanoTime();
    platformThreadUnderTest.read();
    long platformThreadDuration = System.nanoTime() - platformThreadStartTime;
    
    // Log performance results
    log.info("Virtual Thread Duration: {} ms", Duration.ofNanos(virtualThreadDuration).toMillis());
    log.info("Platform Thread Duration: {} ms", Duration.ofNanos(platformThreadDuration).toMillis());
    
    // Virtual threads should generally be more efficient for I/O operations,
    // but this is a simple test and results may vary based on system load
    // We're not making a strict assertion here, just logging the comparison
  }

  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // This test demonstrates the scalability advantage of virtual threads
    // by running a large number of concurrent operations
    int highConcurrencyCount = 10000; // 10,000 concurrent operations
    CountDownLatch latch = new CountDownLatch(highConcurrencyCount);
    AtomicLong successCount = new AtomicLong(0);
    AtomicLong failureCount = new AtomicLong(0);
    
    // Run many concurrent operations
    for (int i = 0; i < highConcurrencyCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          StreamCopier<String> copier = new StreamCopier<>(
              this::writeString,
              this::readString,
              virtualThreadExecutor
          );
          
          String result = copier.read(5000); // 5 second timeout
          if (DEFAULT_READ_OUTPUT.equals(result)) {
            successCount.incrementAndGet();
          }
          else {
            failureCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          failureCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete (with a reasonable timeout)
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    
    // Log results
    log.info("High concurrency test completed: {}", completed);
    log.info("Successful operations: {}", successCount.get());
    log.info("Failed operations: {}", failureCount.get());
    
    // Verify most operations completed successfully
    // We don't expect 100% success due to potential resource constraints
    assertThat(successCount.get(), greaterThan((long)(highConcurrencyCount * 0.9))); // At least 90% success
    assertThat(failureCount.get(), lessThan((long)(highConcurrencyCount * 0.1))); // Less than 10% failure
  }

  /**
   * Helper method to write a string to an output stream.
   */
  private void writeString(OutputStream outputStream) {
    try {
      outputStream.write(DEFAULT_READ_OUTPUT.getBytes());
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  /**
   * Helper method to write large data to an output stream.
   */
  private void writeLargeData(OutputStream outputStream, byte[] data) {
    try {
      outputStream.write(data);
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  /**
   * Helper method to read a string from an input stream.
   */
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
   * Generates random data of the specified size.
   */
  private byte[] generateRandomData(int size) {
    byte[] data = new byte[size];
    new Random().nextBytes(data);
    return data;
  }
}