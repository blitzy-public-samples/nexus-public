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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.nexus.testcommon.Java21TestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for {@link ParallelMultiHashingInputStream} with both platform threads and virtual threads.
 *
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class ParallelMultiHashingInputStreamTest
{
  private static final int SMALL_DATA_SIZE = 100;
  private static final int MEDIUM_DATA_SIZE = 10_000;
  private static final int LARGE_DATA_SIZE = 1_000_000;
  private static final int CONCURRENT_TASKS = 1000;
  private static final int TIMEOUT_SECONDS = 30;

  @Test
  public void sha512IsAccurate() throws IOException {
    byte[] bytes = new byte[SMALL_DATA_SIZE];

    final ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(bytes);
    final HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);

    assertThat(hashCode.toString(), is(equalTo(
        "f206f4f0ef09b90837f1d15a07c6cf4bd291d817663f9f85a0fc4341ec19910719ad571b6102a366ae848cd0f187d0daef912e05898b82c35213cd49a45ee8e0")));
  }

  @Test
  public void countIsAccurate() throws IOException {
    final long byteArrayLength = SMALL_DATA_SIZE;

    ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(new byte[(int) byteArrayLength]);
    assertThat(hashingStream.count(), is(equalTo(byteArrayLength)));
  }

  /**
   * Tests hashing with medium-sized data using platform threads via ForkJoinPool.
   */
  @Test
  public void hashMediumDataWithPlatformThreads() throws Exception {
    byte[] data = generateRandomData(MEDIUM_DATA_SIZE);
    long startTime = System.currentTimeMillis();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = new ForkJoinPool()) {
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
            if (hashingStream.count() == data.length) {
              successCount.incrementAndGet();
            }
          }
          catch (IOException e) {
            // Count as failure
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertThat("Tasks should complete within timeout", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    long platformThreadTime = System.currentTimeMillis() - startTime;
    System.out.println("Platform thread execution time for medium data: " + platformThreadTime + "ms");
    assertThat("All tasks should complete successfully", successCount.get(), is(taskCount));
  }

  /**
   * Tests hashing with medium-sized data using virtual threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void hashMediumDataWithVirtualThreads() throws Exception {
    byte[] data = generateRandomData(MEDIUM_DATA_SIZE);
    long startTime = System.currentTimeMillis();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
            if (hashingStream.count() == data.length) {
              successCount.incrementAndGet();
            }
          }
          catch (IOException e) {
            // Count as failure
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertThat("Tasks should complete within timeout", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    long virtualThreadTime = System.currentTimeMillis() - startTime;
    System.out.println("Virtual thread execution time for medium data: " + virtualThreadTime + "ms");
    assertThat("All tasks should complete successfully", successCount.get(), is(taskCount));
  }

  /**
   * Tests hashing with large-sized data using platform threads via ForkJoinPool.
   */
  @Test
  public void hashLargeDataWithPlatformThreads() throws Exception {
    byte[] data = generateRandomData(LARGE_DATA_SIZE);
    long startTime = System.currentTimeMillis();
    
    int taskCount = 20;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = new ForkJoinPool()) {
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
            if (hashingStream.count() == data.length) {
              successCount.incrementAndGet();
            }
          }
          catch (IOException e) {
            // Count as failure
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertThat("Tasks should complete within timeout", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    long platformThreadTime = System.currentTimeMillis() - startTime;
    System.out.println("Platform thread execution time for large data: " + platformThreadTime + "ms");
    assertThat("All tasks should complete successfully", successCount.get(), is(taskCount));
  }

  /**
   * Tests hashing with large-sized data using virtual threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void hashLargeDataWithVirtualThreads() throws Exception {
    byte[] data = generateRandomData(LARGE_DATA_SIZE);
    long startTime = System.currentTimeMillis();
    
    int taskCount = 20;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
            if (hashingStream.count() == data.length) {
              successCount.incrementAndGet();
            }
          }
          catch (IOException e) {
            // Count as failure
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertThat("Tasks should complete within timeout", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    long virtualThreadTime = System.currentTimeMillis() - startTime;
    System.out.println("Virtual thread execution time for large data: " + virtualThreadTime + "ms");
    assertThat("All tasks should complete successfully", successCount.get(), is(taskCount));
  }

  /**
   * Tests high concurrency with many virtual threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void highConcurrencyWithVirtualThreads() throws Exception {
    byte[] data = generateRandomData(SMALL_DATA_SIZE);
    long startTime = System.currentTimeMillis();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_TASKS; i++) {
        executor.submit(() -> {
          try {
            ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
            if (hashingStream.count() == data.length) {
              successCount.incrementAndGet();
            }
          }
          catch (IOException e) {
            // Count as failure
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertThat("Tasks should complete within timeout", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    long executionTime = System.currentTimeMillis() - startTime;
    System.out.println("Executed " + CONCURRENT_TASKS + " concurrent tasks in " + executionTime + "ms");
    assertThat("All tasks should complete successfully", successCount.get(), is(CONCURRENT_TASKS));
  }

  /**
   * Compares performance between ForkJoinPool and Virtual Threads for the same workload.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void comparePerformanceBetweenForkJoinPoolAndVirtualThreads() throws Exception {
    byte[] data = generateRandomData(MEDIUM_DATA_SIZE);
    int taskCount = 500;
    
    // Test with ForkJoinPool
    long fjpStartTime = System.currentTimeMillis();
    CountDownLatch fjpLatch = new CountDownLatch(taskCount);
    AtomicInteger fjpSuccessCount = new AtomicInteger(0);
    
    try (ExecutorService fjpExecutor = new ForkJoinPool()) {
      for (int i = 0; i < taskCount; i++) {
        fjpExecutor.submit(() -> {
          try {
            ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
            if (hashingStream.count() == data.length) {
              fjpSuccessCount.incrementAndGet();
            }
          }
          catch (IOException e) {
            // Count as failure
          }
          finally {
            fjpLatch.countDown();
          }
        });
      }
      
      assertThat("FJP tasks should complete within timeout", 
          fjpLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    long fjpExecutionTime = System.currentTimeMillis() - fjpStartTime;
    System.out.println("ForkJoinPool execution time: " + fjpExecutionTime + "ms");
    
    // Test with Virtual Threads
    long vtStartTime = System.currentTimeMillis();
    CountDownLatch vtLatch = new CountDownLatch(taskCount);
    AtomicInteger vtSuccessCount = new AtomicInteger(0);
    
    try (ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < taskCount; i++) {
        vtExecutor.submit(() -> {
          try {
            ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
            if (hashingStream.count() == data.length) {
              vtSuccessCount.incrementAndGet();
            }
          }
          catch (IOException e) {
            // Count as failure
          }
          finally {
            vtLatch.countDown();
          }
        });
      }
      
      assertThat("Virtual Thread tasks should complete within timeout", 
          vtLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }
    
    long vtExecutionTime = System.currentTimeMillis() - vtStartTime;
    System.out.println("Virtual Thread execution time: " + vtExecutionTime + "ms");
    
    // Verify both completed successfully
    assertThat("All FJP tasks should complete successfully", fjpSuccessCount.get(), is(taskCount));
    assertThat("All Virtual Thread tasks should complete successfully", vtSuccessCount.get(), is(taskCount));
    
    // Log performance comparison
    System.out.println("Performance difference: " + 
        (fjpExecutionTime > vtExecutionTime ? 
            "Virtual Threads were " + (fjpExecutionTime * 100 / vtExecutionTime - 100) + "% faster" :
            "ForkJoinPool was " + (vtExecutionTime * 100 / fjpExecutionTime - 100) + "% faster"));
  }

  /**
   * Tests scaling efficiency of virtual threads with increasing data sizes.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void virtualThreadScalingEfficiency() throws Exception {
    List<Integer> dataSizes = Arrays.asList(1_000, 10_000, 100_000, 1_000_000);
    int taskCount = 50;
    
    List<Long> executionTimes = new ArrayList<>();
    
    for (Integer dataSize : dataSizes) {
      byte[] data = generateRandomData(dataSize);
      long startTime = System.currentTimeMillis();
      
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < taskCount; i++) {
          executor.submit(() -> {
            try {
              ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(data);
              if (hashingStream.count() == data.length) {
                successCount.incrementAndGet();
              }
            }
            catch (IOException e) {
              // Count as failure
            }
            finally {
              latch.countDown();
            }
          });
        }
        
        assertThat("Tasks should complete within timeout", 
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      }
      
      long executionTime = System.currentTimeMillis() - startTime;
      executionTimes.add(executionTime);
      
      System.out.println("Data size: " + dataSize + " bytes, execution time: " + executionTime + "ms");
      assertThat("All tasks should complete successfully", successCount.get(), is(taskCount));
    }
    
    // Verify scaling is sub-linear (efficiency improves with larger data sizes)
    for (int i = 1; i < executionTimes.size(); i++) {
      long previousTime = executionTimes.get(i - 1);
      long currentTime = executionTimes.get(i);
      int previousSize = dataSizes.get(i - 1);
      int currentSize = dataSizes.get(i);
      
      // Calculate scaling factor (should be less than linear)
      double scalingFactor = (double) currentTime / previousTime;
      double sizeFactor = (double) currentSize / previousSize;
      
      System.out.println("Scaling factor from " + previousSize + " to " + currentSize + 
          " bytes: " + scalingFactor + " (size increased by " + sizeFactor + "x)");
      
      // Scaling should be sub-linear (less than the increase in data size)
      assertThat("Scaling should be sub-linear", scalingFactor, is(lessThan(sizeFactor)));
    }
  }

  private ParallelMultiHashingInputStream createAndUseHashingStream(final byte[] bytes) throws IOException {
    final ParallelMultiHashingInputStream hashingStream = new ParallelMultiHashingInputStream(
        Arrays.asList(HashAlgorithm.SHA512), new ByteArrayInputStream(bytes));

    ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
    return hashingStream;
  }
  
  private byte[] generateRandomData(int size) {
    byte[] data = new byte[size];
    new Random().nextBytes(data);
    return data;
  }
}
