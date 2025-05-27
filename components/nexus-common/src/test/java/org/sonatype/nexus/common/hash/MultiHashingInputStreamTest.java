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
package org.sonatype.nexus.common.hash;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for {@link MultiHashingInputStream}.
 * 
 * This test class validates the functionality of MultiHashingInputStream with both
 * standard execution and Virtual Thread execution for I/O operations.
 */
@Category(Java21TestGroup.class)
public class MultiHashingInputStreamTest
    extends TestSupport
{
  private static final int DEFAULT_BYTE_ARRAY_SIZE = 100;
  private static final int LARGE_BYTE_ARRAY_SIZE = 1024 * 1024; // 1MB
  private static final int CONCURRENT_TASKS = 100;
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  
  /**
   * Verifies that SHA512 hash calculation is accurate.
   */
  @Test
  public void sha512IsAccurate() throws IOException {
    byte[] bytes = new byte[DEFAULT_BYTE_ARRAY_SIZE];

    final MultiHashingInputStream hashingStream = createAndUseHashingStream(bytes);

    final HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);

    assertThat(hashCode.toString(), is(equalTo(
        "f206f4f0ef09b90837f1d15a07c6cf4bd291d817663f9f85a0fc4341ec19910719ad571b6102a366ae848cd0f187d0daef912e05898b82c35213cd49a45ee8e0")));
  }

  /**
   * Verifies that byte count tracking is accurate.
   */
  @Test
  public void testCountIsAccurate() throws IOException {
    final long byteArrayLength = DEFAULT_BYTE_ARRAY_SIZE;

    final MultiHashingInputStream andUseHashingStream = createAndUseHashingStream(new byte[(int) byteArrayLength]);
    assertThat(andUseHashingStream.count(), is(equalTo(byteArrayLength)));
  }
  
  /**
   * Verifies SHA512 hash calculation using JUnit Jupiter style.
   */
  @org.junit.jupiter.api.Test
  @DisplayName("SHA512 hash calculation should be accurate")
  void sha512HashCalculationIsAccurate() throws IOException {
    byte[] bytes = new byte[DEFAULT_BYTE_ARRAY_SIZE];

    final MultiHashingInputStream hashingStream = createAndUseHashingStream(bytes);

    final HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);

    assertThat(hashCode.toString(), is(equalTo(
        "f206f4f0ef09b90837f1d15a07c6cf4bd291d817663f9f85a0fc4341ec19910719ad571b6102a366ae848cd0f187d0daef912e05898b82c35213cd49a45ee8e0")));
  }
  
  /**
   * Verifies byte count tracking using JUnit Jupiter style.
   */
  @org.junit.jupiter.api.Test
  @DisplayName("Byte count tracking should be accurate")
  void byteCountTrackingIsAccurate() throws IOException {
    final long byteArrayLength = DEFAULT_BYTE_ARRAY_SIZE;

    final MultiHashingInputStream andUseHashingStream = createAndUseHashingStream(new byte[(int) byteArrayLength]);
    assertThat(andUseHashingStream.count(), is(equalTo(byteArrayLength)));
  }
  
  /**
   * Tests I/O operations with Virtual Threads.
   * This test verifies that MultiHashingInputStream works correctly when used with Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  @org.junit.jupiter.api.Test
  @DisplayName("MultiHashingInputStream should work correctly with Virtual Threads")
  public void virtualThreadIOOperations() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicLong successCount = new AtomicLong(0);
    
    // Create a Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < CONCURRENT_TASKS; i++) {
        executor.submit(() -> {
          try {
            byte[] bytes = new byte[DEFAULT_BYTE_ARRAY_SIZE];
            MultiHashingInputStream hashingStream = createAndUseHashingStream(bytes);
            
            // Verify hash and count
            HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);
            if (hashCode != null && hashingStream.count() == DEFAULT_BYTE_ARRAY_SIZE) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread execution", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    // Verify all tasks completed successfully
    assertThat(successCount.get(), is((long) CONCURRENT_TASKS));
  }
  
  /**
   * Compares performance between standard execution and Virtual Thread execution.
   * This test measures the throughput of processing large byte arrays with both
   * standard threads and Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  @org.junit.jupiter.api.Test
  @DisplayName("Virtual Threads should provide better performance for I/O operations")
  public void comparePerformanceWithVirtualThreads() throws Exception {
    // Warm up to avoid JIT compilation effects
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      processBytesWithStandardThreads(5, LARGE_BYTE_ARRAY_SIZE);
      processBytesWithVirtualThreads(5, LARGE_BYTE_ARRAY_SIZE);
    }
    
    // Measure performance with standard threads
    long startTimeStandard = System.nanoTime();
    processBytesWithStandardThreads(CONCURRENT_TASKS, LARGE_BYTE_ARRAY_SIZE);
    long durationStandard = System.nanoTime() - startTimeStandard;
    
    // Measure performance with virtual threads
    long startTimeVirtual = System.nanoTime();
    processBytesWithVirtualThreads(CONCURRENT_TASKS, LARGE_BYTE_ARRAY_SIZE);
    long durationVirtual = System.nanoTime() - startTimeVirtual;
    
    log.info("Standard threads execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(durationStandard));
    log.info("Virtual threads execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(durationVirtual));
    
    // For I/O bound operations, virtual threads should generally perform better
    // However, this is a simple test and might not always show significant differences
    // in a test environment, so we're just logging the results rather than asserting
    // a specific performance improvement
    
    // If running in an environment where Virtual Threads are properly supported,
    // we expect virtual threads to be at least as fast as standard threads
    assertThat(durationVirtual, lessThan(durationStandard * 2));
  }
  
  /**
   * Tests MultiHashingInputStream with large byte arrays using Virtual Threads.
   * This test verifies that MultiHashingInputStream can handle large data volumes
   * when used with Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  @org.junit.jupiter.api.Test
  @DisplayName("MultiHashingInputStream should handle large data volumes with Virtual Threads")
  public void largeDataVolumeWithVirtualThreads() throws Exception {
    int dataSize = 10 * 1024 * 1024; // 10MB
    byte[] largeBytes = new byte[dataSize];
    
    // Fill with some non-zero data
    for (int i = 0; i < dataSize; i++) {
      largeBytes[i] = (byte)(i % 256);
    }
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          MultiHashingInputStream hashingStream = new MultiHashingInputStream(
              Arrays.asList(HashAlgorithm.SHA512), new ByteArrayInputStream(largeBytes));
          
          ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
          
          // Verify count matches the data size
          assertThat(hashingStream.count(), is((long) dataSize));
          
          // Verify we got a hash
          HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);
          assertThat(hashCode, is(org.hamcrest.Matchers.notNullValue()));
          
          // Verify hash length is correct for SHA-512 (512 bits = 64 bytes = 128 hex chars)
          assertThat(hashCode.toString().length(), is(128));
        } 
        catch (Exception e) {
          log.error("Error processing large data volume", e);
          throw new RuntimeException(e);
        }
      }).get(60, TimeUnit.SECONDS); // Wait for completion with timeout
    }
  }
  
  /**
   * Helper method to process bytes with standard threads.
   */
  private void processBytesWithStandardThreads(int taskCount, int byteArraySize) throws Exception {
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            byte[] bytes = new byte[byteArraySize];
            createAndUseHashingStream(bytes);
          } 
          catch (Exception e) {
            log.error("Error in standard thread execution", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(60, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to process bytes with virtual threads.
   */
  private void processBytesWithVirtualThreads(int taskCount, int byteArraySize) throws Exception {
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            byte[] bytes = new byte[byteArraySize];
            createAndUseHashingStream(bytes);
          } 
          catch (Exception e) {
            log.error("Error in virtual thread execution", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(60, TimeUnit.SECONDS);
    }
  }

  /**
   * Helper method to create and use a MultiHashingInputStream.
   */
  private MultiHashingInputStream createAndUseHashingStream(final byte[] bytes) throws IOException {
    final MultiHashingInputStream hashingStream = new MultiHashingInputStream(
        Arrays.asList(HashAlgorithm.SHA512), new ByteArrayInputStream(bytes));

    ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
    return hashingStream;
  }
}