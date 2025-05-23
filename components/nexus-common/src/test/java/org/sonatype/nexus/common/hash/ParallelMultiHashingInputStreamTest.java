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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.nexus.common.thread.Java21TestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link ParallelMultiHashingInputStream} with Java 21 Virtual Thread support.
 *
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class ParallelMultiHashingInputStreamTest
{
  private static final int SMALL_DATA_SIZE = 100;
  private static final int MEDIUM_DATA_SIZE = 1024 * 1024; // 1MB
  private static final int LARGE_DATA_SIZE = 10 * 1024 * 1024; // 10MB
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int WARMUP_ITERATIONS = 5;
  private static final int BENCHMARK_ITERATIONS = 10;

  @Test
  public void sha512IsAccurate() throws IOException {
    byte[] bytes = new byte[SMALL_DATA_SIZE];

    final ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(bytes);
    final HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);

    assertThat(hashCode.toString(), is(equalTo(
        "f206f4f0ef09b90837f1d15a07c6cf4bd291d817663f9f85a0fc4341ec19910719ad571b6102a366ae848cd0f187d0daef912e05898b82c35213cd49a45ee8e0")));
  }

  @Test
  public void shouldReportAccurateByteCount() throws IOException {
    final long byteArrayLength = SMALL_DATA_SIZE;

    ParallelMultiHashingInputStream hashingStream = createAndUseHashingStream(new byte[(int) byteArrayLength]);
    assertThat(hashingStream.count(), is(equalTo(byteArrayLength)));
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void shouldHashDataWithVirtualThreads() throws IOException {
    // Given a data array
    byte[] bytes = new byte[MEDIUM_DATA_SIZE];
    new Random().nextBytes(bytes);
    
    // When hashing with virtual threads
    ParallelMultiHashingInputStream hashingStream = createAndUseHashingStreamWithVirtualThreads(bytes);
    
    // Then the hash should be calculated correctly
    HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);
    assertThat(hashCode, is(notNullValue()));
    assertThat(hashingStream.count(), is(equalTo((long) bytes.length)));
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void shouldScaleWithDifferentDataSizes() throws IOException {
    // Test with small data size
    byte[] smallData = new byte[SMALL_DATA_SIZE];
    new Random().nextBytes(smallData);
    long smallDataTime = measureHashingTime(smallData);
    
    // Test with medium data size
    byte[] mediumData = new byte[MEDIUM_DATA_SIZE];
    new Random().nextBytes(mediumData);
    long mediumDataTime = measureHashingTime(mediumData);
    
    // Test with large data size
    byte[] largeData = new byte[LARGE_DATA_SIZE];
    new Random().nextBytes(largeData);
    long largeDataTime = measureHashingTime(largeData);
    
    // Verify scaling is reasonable (not strictly linear due to thread overhead)
    assertThat(mediumDataTime, is(greaterThan(smallDataTime)));
    assertThat(largeDataTime, is(greaterThan(mediumDataTime)));
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void shouldHandleConcurrentOperations() throws Exception {
    // Given a large number of concurrent hashing operations
    int concurrentTasks = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    ExecutorService executor = VirtualThreadTestSupport.newVirtualThreadExecutor("hash-test");
    List<Future<HashCode>> futures = new ArrayList<>();
    
    // When executing them concurrently
    for (int i = 0; i < concurrentTasks; i++) {
      final int size = MEDIUM_DATA_SIZE;
      final int seed = i;
      
      futures.add(executor.submit(() -> {
        try {
          byte[] data = new byte[size];
          new Random(seed).nextBytes(data);
          ParallelMultiHashingInputStream hashingStream = createAndUseHashingStreamWithVirtualThreads(data);
          return hashingStream.hashes().get(HashAlgorithm.SHA512);
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Then all operations should complete successfully
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();
    
    assertThat("All concurrent operations should complete", completed, is(true));
    
    // And all hashes should be calculated
    for (Future<HashCode> future : futures) {
      HashCode hashCode = future.get();
      assertThat(hashCode, is(notNullValue()));
    }
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void shouldComparePerformanceWithForkJoinPool() throws Exception {
    // Warm up to avoid JIT compilation effects
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      byte[] data = new byte[MEDIUM_DATA_SIZE];
      new Random().nextBytes(data);
      createAndUseHashingStream(data); // Using ForkJoinPool
      createAndUseHashingStreamWithVirtualThreads(data); // Using Virtual Threads
    }
    
    // Measure ForkJoinPool performance
    long forkJoinPoolTime = 0;
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      byte[] data = new byte[LARGE_DATA_SIZE];
      new Random().nextBytes(data);
      long start = System.nanoTime();
      createAndUseHashingStream(data);
      forkJoinPoolTime += System.nanoTime() - start;
    }
    forkJoinPoolTime /= BENCHMARK_ITERATIONS;
    
    // Measure Virtual Threads performance
    long virtualThreadsTime = 0;
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      byte[] data = new byte[LARGE_DATA_SIZE];
      new Random().nextBytes(data);
      long start = System.nanoTime();
      createAndUseHashingStreamWithVirtualThreads(data);
      virtualThreadsTime += System.nanoTime() - start;
    }
    virtualThreadsTime /= BENCHMARK_ITERATIONS;
    
    // Virtual Threads should be at least as fast as ForkJoinPool for I/O bound operations
    // Note: This is a relative comparison, not an absolute requirement
    double ratio = (double) virtualThreadsTime / forkJoinPoolTime;
    
    // Log performance metrics for analysis
    System.out.println("ForkJoinPool average time (ns): " + forkJoinPoolTime);
    System.out.println("Virtual Threads average time (ns): " + virtualThreadsTime);
    System.out.println("Performance ratio (Virtual/ForkJoin): " + ratio);
    
    // Virtual threads might have some overhead but shouldn't be significantly slower
    // Allow up to 20% overhead for this test
    assertThat(ratio, is(lessThanOrEqualTo(1.2)));
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void shouldHandleMultipleHashAlgorithms() throws IOException {
    // Given data and multiple hash algorithms
    byte[] bytes = new byte[MEDIUM_DATA_SIZE];
    new Random().nextBytes(bytes);
    List<HashAlgorithm> algorithms = Arrays.asList(
        HashAlgorithm.MD5, 
        HashAlgorithm.SHA1, 
        HashAlgorithm.SHA256, 
        HashAlgorithm.SHA512
    );
    
    // When hashing with virtual threads
    ParallelMultiHashingInputStream hashingStream = createAndUseHashingStreamWithVirtualThreads(bytes, algorithms);
    
    // Then all hashes should be calculated correctly
    for (HashAlgorithm algorithm : algorithms) {
      HashCode hashCode = hashingStream.hashes().get(algorithm);
      assertThat("Hash for " + algorithm + " should be calculated", hashCode, is(notNullValue()));
    }
    assertThat(hashingStream.count(), is(equalTo((long) bytes.length)));
  }

  private ParallelMultiHashingInputStream createAndUseHashingStream(final byte[] bytes) throws IOException {
    return createAndUseHashingStream(bytes, Arrays.asList(HashAlgorithm.SHA512));
  }

  private ParallelMultiHashingInputStream createAndUseHashingStream(
      final byte[] bytes, final List<HashAlgorithm> algorithms) throws IOException 
  {
    final ParallelMultiHashingInputStream hashingStream = new ParallelMultiHashingInputStream(
        algorithms, new ByteArrayInputStream(bytes));

    ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
    return hashingStream;
  }

  private ParallelMultiHashingInputStream createAndUseHashingStreamWithVirtualThreads(final byte[] bytes) 
      throws IOException 
  {
    return createAndUseHashingStreamWithVirtualThreads(bytes, Arrays.asList(HashAlgorithm.SHA512));
  }

  private ParallelMultiHashingInputStream createAndUseHashingStreamWithVirtualThreads(
      final byte[] bytes, final List<HashAlgorithm> algorithms) throws IOException 
  {
    // Create a custom executor using virtual threads
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      final ParallelMultiHashingInputStream hashingStream = new ParallelMultiHashingInputStream(
          algorithms, new ByteArrayInputStream(bytes), virtualThreadExecutor);

      ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
      return hashingStream;
    } finally {
      virtualThreadExecutor.shutdown();
    }
  }

  private long measureHashingTime(final byte[] data) throws IOException {
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      long startTime = System.nanoTime();
      
      final ParallelMultiHashingInputStream hashingStream = new ParallelMultiHashingInputStream(
          Arrays.asList(HashAlgorithm.SHA512), new ByteArrayInputStream(data), virtualThreadExecutor);

      ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
      
      return System.nanoTime() - startTime;
    } finally {
      virtualThreadExecutor.shutdown();
    }
  }
}