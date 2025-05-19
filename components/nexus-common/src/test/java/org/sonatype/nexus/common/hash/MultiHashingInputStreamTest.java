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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.virtualthread.VirtualThreadTestGroup;

import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for {@link MultiHashingInputStream}.
 * 
 * @since 3.60.0
 */
@Category(Java21TestGroup.class)
public class MultiHashingInputStreamTest
    extends TestSupport
{
  private static final int BYTE_ARRAY_SIZE = 100;
  private static final String EXPECTED_SHA512_HASH = 
      "f206f4f0ef09b90837f1d15a07c6cf4bd291d817663f9f85a0fc4341ec19910719ad571b6102a366ae848cd0f187d0daef912e05898b82c35213cd49a45ee8e0";

  @Test
  @DisplayName("SHA-512 hash calculation is accurate")
  public void sha512IsAccurate() throws IOException {
    byte[] bytes = new byte[BYTE_ARRAY_SIZE];

    final MultiHashingInputStream hashingStream = createAndUseHashingStream(bytes);

    final HashCode hashCode = hashingStream.hashes().get(HashAlgorithm.SHA512);

    assertThat(hashCode.toString(), is(equalTo(EXPECTED_SHA512_HASH)));
  }

  @Test
  @DisplayName("Byte count is accurate")
  public void testCountIsAccurate() throws IOException {
    final long byteArrayLength = BYTE_ARRAY_SIZE;

    final MultiHashingInputStream andUseHashingStream = createAndUseHashingStream(new byte[(int) byteArrayLength]);
    assertThat(andUseHashingStream.count(), is(equalTo(byteArrayLength)));
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  @DisplayName("Virtual Thread I/O operations work correctly")
  public void testVirtualThreadIO() throws Exception {
    // Create a larger array for more noticeable I/O operations
    byte[] largeBytes = new byte[1024 * 1024]; // 1MB
    
    // Use virtual threads to process the stream
    try (var executor = Thread.ofVirtual().name("hash-test-", 0).factory().newExecutor()) {
      Future<HashCode> future = executor.submit(() -> {
        try {
          MultiHashingInputStream hashingStream = createAndUseHashingStream(largeBytes);
          return hashingStream.hashes().get(HashAlgorithm.SHA512);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      HashCode hashCode = future.get(5, TimeUnit.SECONDS);
      assertThat(hashCode, is(equalTo(calculateExpectedHash(largeBytes))));
    }
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  @DisplayName("Performance comparison between platform and virtual threads")
  public void testPerformanceComparison() throws Exception {
    int iterations = 100;
    byte[] testData = new byte[1024 * 100]; // 100KB
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
        List<Future<HashCode>> futures = IntStream.range(0, iterations)
            .mapToObj(i -> executor.submit(() -> {
              try {
                return createAndUseHashingStream(testData).hashes().get(HashAlgorithm.SHA512);
              }
              catch (IOException e) {
                throw new RuntimeException(e);
              }
            }))
            .collect(Collectors.toList());
        
        // Wait for all tasks to complete
        for (Future<HashCode> future : futures) {
          future.get();
        }
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (var executor = Thread.ofVirtual().name("hash-test-", 0).factory().newExecutor()) {
        List<Future<HashCode>> futures = IntStream.range(0, iterations)
            .mapToObj(i -> executor.submit(() -> {
              try {
                return createAndUseHashingStream(testData).hashes().get(HashAlgorithm.SHA512);
              }
              catch (IOException e) {
                throw new RuntimeException(e);
              }
            }))
            .collect(Collectors.toList());
        
        // Wait for all tasks to complete
        for (Future<HashCode> future : futures) {
          future.get();
        }
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should generally be more efficient for I/O operations
    // but this is not guaranteed in all environments, so we don't assert on it
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  @DisplayName("Multiple concurrent virtual thread I/O operations")
  public void testConcurrentVirtualThreadIO() throws Exception {
    int concurrentTasks = 1000; // Run 1000 concurrent tasks
    byte[] data = new byte[1024]; // 1KB per task
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    
    try (var executor = Thread.ofVirtual().name("hash-test-", 0).factory().newExecutor()) {
      // Submit many concurrent tasks
      for (int i = 0; i < concurrentTasks; i++) {
        executor.submit(() -> {
          try {
            createAndUseHashingStream(data);
            latch.countDown();
            return true;
          }
          catch (Exception e) {
            latch.countDown();
            throw new RuntimeException(e);
          }
        });
      }
      
      // Wait for all tasks to complete with timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual thread tasks should complete in time", completed, is(true));
    }
  }

  private MultiHashingInputStream createAndUseHashingStream(final byte[] bytes) throws IOException {
    final MultiHashingInputStream hashingStream = new MultiHashingInputStream(
        Arrays.asList(HashAlgorithm.SHA512), new ByteArrayInputStream(bytes));

    ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
    return hashingStream;
  }
  
  private HashCode calculateExpectedHash(byte[] bytes) throws IOException {
    MultiHashingInputStream hashingStream = new MultiHashingInputStream(
        Arrays.asList(HashAlgorithm.SHA512), new ByteArrayInputStream(bytes));
    ByteStreams.copy(hashingStream, ByteStreams.nullOutputStream());
    return hashingStream.hashes().get(HashAlgorithm.SHA512);
  }
  
  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
}