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
package org.sonatype.nexus.repository.maven.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadTestGroup;

import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link DigestExtractor} with Java 21 Virtual Threads
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class DigestExtractorVirtualThreadTest
    extends TestSupport
{
  private String[][] validDigests =
      {
          {"MD5 (pom.xml) = 68da13206e9dcce2db9ec45a9f7acd52", "68da13206e9dcce2db9ec45a9f7acd52"},
          {"68da13206e9dcce2db9ec45a9f7acd52 pom.xml", "68da13206e9dcce2db9ec45a9f7acd52"},
          {"68da13206e9dcce2db9ec45a9f7acd52        pom.xml", "68da13206e9dcce2db9ec45a9f7acd52"},
          {"93f402a80b5c40b7f32f68771ee57c27", "93f402a80b5c40b7f32f68771ee57c27"},
          {"bbb603f9f7a32a10eb539c1067992dabab58d33a", "bbb603f9f7a32a10eb539c1067992dabab58d33a"},
          {"ant-1.5.jar: 90 2A 36 0E CA D9 8A 34  B5 98 63 C1 E6 5B CF 71", "902a360ecad98a34b59863c1e65bcf71"},
          {
              "ant-1.5.jar: DCAB 88FC 2A04 3C24 79A6  DE67 6A2F 8179 E9EA 2167",
              "dcab88fc2a043c2479a6de676a2f8179e9ea2167"
          },
          {"90 2A 36 0E CA D9 8A 34  B5 98 63 C1 E6 5B CF 71", "902a360ecad98a34b59863c1e65bcf71"},
          {"DCAB 88FC 2A04 3C24 79A6  DE67 6A2F 8179 E9EA 2167", "dcab88fc2a043c2479a6de676a2f8179e9ea2167"},
          {"90 2A 36 0E CA D9 8A 34  B5 98 63 C1 E6 5B CF 71     pom.xml", "902a360ecad98a34b59863c1e65bcf71"},
          {
              "DCAB 88FC 2A04 3C24 79A6  DE67 6A2F 8179 E9EA 2167     pom.xml",
              "dcab88fc2a043c2479a6de676a2f8179e9ea2167"
          },
          {
            "f34c7a1713f8fdf823a79de7ed76c8dd034d04769f591dc3df44a0cffe82d8a75001ae033570db45a03641281cb4a7e09a961f580426b798d50e8fbfcd7506aa",
            "f34c7a1713f8fdf823a79de7ed76c8dd034d04769f591dc3df44a0cffe82d8a75001ae033570db45a03641281cb4a7e09a961f580426b798d50e8fbfcd7506aa"
          },
      };

  private InputStream stream(String string) throws IOException
  {
    return new ByteArrayInputStream(string.getBytes("UTF-8"));
  }

  /**
   * Tests that the DigestExtractor correctly extracts digests from valid inputs
   * when accessed concurrently using Virtual Threads.
   */
  @Test
  public void acceptedDigestsWithVirtualThreads() throws Exception
  {
    // Number of concurrent threads to use
    int threadCount = validDigests.length * 10; // Run each digest test multiple times
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i % validDigests.length;
        executor.submit(() -> {
          try {
            String test = validDigests[index][0];
            String expected = validDigests[index][1];

            String digest = DigestExtractor.extract(stream(test));

            if (digest != null && digest.equals(expected)) {
              successCount.incrementAndGet();
            } else {
              firstException.compareAndSet(null, new AssertionError(
                  "DigestExtractor did not accept " + test + ", got " + digest + ", expected " + expected));
            }
          } catch (Exception e) {
            firstException.compareAndSet(null, e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Check if any exceptions occurred
      if (firstException.get() != null) {
        throw firstException.get();
      }

      // Verify all threads succeeded
      assertEquals(threadCount, successCount.get(), "All virtual threads should have succeeded");
    }
  }

  /**
   * Tests that the DigestExtractor correctly rejects invalid digests
   * when accessed concurrently using Virtual Threads.
   */
  @Test
  public void rejectedDigestsWithVirtualThreads() throws Exception {
    // Invalid digest strings to test
    String[] invalidDigests = {
        "123456", // too short
        "", // empty
        "   ", // blank
        "902a360Xcad98a34b59863c1e65bcf71" // invalid, there is an non-hex X in there
    };

    // Number of concurrent threads to use
    int threadsPerDigest = 25;
    int threadCount = invalidDigests.length * threadsPerDigest;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger rejectionCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i % invalidDigests.length;
        executor.submit(() -> {
          try {
            String test = invalidDigests[index];
            String digest = DigestExtractor.extract(test);

            if (digest == null) {
              rejectionCount.incrementAndGet();
            } else {
              firstException.compareAndSet(null, new AssertionError(
                  "DigestExtractor accepted invalid digest: " + test + ", got " + digest));
            }
          } catch (Exception e) {
            firstException.compareAndSet(null, e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Check if any exceptions occurred
      if (firstException.get() != null) {
        throw firstException.get();
      }

      // Verify all threads had their digests rejected
      assertEquals(threadCount, rejectionCount.get(), "All invalid digests should have been rejected");
    }
  }

  /**
   * Tests that the DigestExtractor correctly handles a mix of valid and invalid digests
   * when accessed concurrently using Virtual Threads.
   */
  @Test
  public void mixedDigestsWithVirtualThreads() throws Exception {
    // Number of concurrent threads to use
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger validCount = new AtomicInteger(0);
    AtomicInteger invalidCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            if (index % 2 == 0) {
              // Valid digest
              int validIndex = (index / 2) % validDigests.length;
              String test = validDigests[validIndex][0];
              String expected = validDigests[validIndex][1];

              String digest = DigestExtractor.extract(stream(test));

              if (digest != null && digest.equals(expected)) {
                validCount.incrementAndGet();
              } else {
                firstException.compareAndSet(null, new AssertionError(
                    "DigestExtractor did not accept valid digest: " + test));
              }
            } else {
              // Invalid digest
              String test = "invalid-digest-" + index;
              String digest = DigestExtractor.extract(test);

              if (digest == null) {
                invalidCount.incrementAndGet();
              } else {
                firstException.compareAndSet(null, new AssertionError(
                    "DigestExtractor accepted invalid digest: " + test));
              }
            }
          } catch (Exception e) {
            firstException.compareAndSet(null, e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Check if any exceptions occurred
      if (firstException.get() != null) {
        throw firstException.get();
      }

      // Verify correct counts
      assertEquals(threadCount / 2, validCount.get(), "Valid digest count should match");
      assertEquals(threadCount / 2, invalidCount.get(), "Invalid digest count should match");
    }
  }

  /**
   * Tests that the DigestExtractor correctly handles concurrent extraction from InputStreams
   * using Virtual Threads.
   */
  @Test
  public void concurrentInputStreamExtractionWithVirtualThreads() throws Exception {
    // Number of concurrent threads to use
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Create CompletableFutures for each thread
    CompletableFuture<?>[] futures = new CompletableFuture[threadCount];

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i % validDigests.length;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();

            String test = validDigests[index][0];
            String expected = validDigests[index][1];

            // Create a new stream for each extraction
            String digest = DigestExtractor.extract(stream(test));

            if (digest != null && digest.equals(expected)) {
              successCount.incrementAndGet();
            } else {
              firstException.compareAndSet(null, new AssertionError(
                  "DigestExtractor did not extract correct digest: " + test + ", got " + digest));
            }
          } catch (Exception e) {
            firstException.compareAndSet(null, e);
          } finally {
            completionLatch.countDown();
          }
        }, executor);
      }

      // Start all threads simultaneously
      startLatch.countDown();

      // Wait for all tasks to complete
      completionLatch.await(30, TimeUnit.SECONDS);

      // Check if any exceptions occurred
      if (firstException.get() != null) {
        throw firstException.get();
      }

      // Verify all threads succeeded
      assertEquals(threadCount, successCount.get(), "All virtual threads should have succeeded");
    }
  }

  /**
   * Tests that the DigestExtractor correctly handles IOException during extraction
   * when accessed concurrently using Virtual Threads.
   */
  @Test
  public void ioExceptionHandlingWithVirtualThreads() throws Exception {
    // Create a problematic InputStream that throws IOException
    InputStream problematicStream = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("Simulated IO failure");
      }
    };

    // Number of concurrent threads to use
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger nullResultCount = new AtomicInteger(0);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // DigestExtractor should handle the IOException and return null
            String digest = DigestExtractor.extract(problematicStream);
            if (digest == null) {
              nullResultCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify all threads got null results
      assertEquals(threadCount, nullResultCount.get(), "All extractions should have returned null");
    }
  }
}