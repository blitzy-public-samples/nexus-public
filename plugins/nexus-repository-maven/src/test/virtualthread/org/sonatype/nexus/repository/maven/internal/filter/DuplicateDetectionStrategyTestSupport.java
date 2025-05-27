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
package org.sonatype.nexus.repository.maven.internal.filter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.collect.ImmutableMap;
import org.apache.maven.index.reader.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.apache.maven.index.reader.Record.ARTIFACT_ID;
import static org.apache.maven.index.reader.Record.CLASSIFIER;
import static org.apache.maven.index.reader.Record.FILE_EXTENSION;
import static org.apache.maven.index.reader.Record.GROUP_ID;
import static org.apache.maven.index.reader.Record.Type.ARTIFACT_ADD;
import static org.apache.maven.index.reader.Record.VERSION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test support class for duplicate detection strategies using Virtual Threads.
 * Extends the original test support class with additional methods for testing
 * concurrent operations using Java 21's Virtual Threads.
 */
public class DuplicateDetectionStrategyTestSupport
    extends TestSupport
{
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  public void setUp() {
    // Create executors for both virtual and platform threads for comparison testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    platformThreadExecutor = Executors.newCachedThreadPool();
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    // Clean up executors
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Verifies duplicate detection functionality with a single thread.
   * This method is compatible with the original implementation for backward compatibility.
   */
  public void verifyDuplicateDetection(final DuplicateDetectionStrategy<Record> strategy) throws Exception {
    int expectedUnique = 3;

    for (int i = 1; i <= expectedUnique; i++) {
      Record uniqueRecord = buildRecord("group1", "artifact" + i, "1.0", "sources", "jar");
      assertTrue(strategy.apply(uniqueRecord));
    }

    for (int i = 0; i < 10; i++) {
      Record duplicateRecord = buildRecord("group1", "artifact" + expectedUnique, "1.0", "sources", "jar");
      assertFalse(strategy.apply(duplicateRecord));
    }

    strategy.close();
  }
  
  /**
   * Verifies duplicate detection functionality with multiple concurrent virtual threads.
   * This tests the strategy's behavior under high concurrency using Virtual Threads.
   *
   * @param strategy The duplicate detection strategy to test
   * @param threadCount The number of concurrent threads to use
   * @param recordsPerThread The number of records each thread should process
   */
  public void verifyDuplicateDetectionWithVirtualThreads(
      final DuplicateDetectionStrategy<Record> strategy,
      final int threadCount,
      final int recordsPerThread) throws Exception {
    
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger uniqueCount = new AtomicInteger(0);
    AtomicInteger duplicateCount = new AtomicInteger(0);
    
    // Create and start virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // First half of threads add unique records, second half try duplicates
          boolean addUnique = threadId < threadCount / 2;
          
          for (int i = 0; i < recordsPerThread; i++) {
            String artifactId = "artifact" + (addUnique ? (threadId * 1000 + i) : (i % 10));
            Record record = buildRecord("group1", artifactId, "1.0", "sources", "jar");
            
            boolean isUnique = strategy.apply(record);
            
            if (addUnique) {
              if (isUnique) {
                uniqueCount.incrementAndGet();
              } else {
                // This should be unique but was detected as duplicate
                errorCount.incrementAndGet();
              }
            } else {
              if (!isUnique) {
                duplicateCount.incrementAndGet();
              } else {
                // This should be a duplicate but was detected as unique
                errorCount.incrementAndGet();
              }
            }
          }
        } catch (Exception e) {
          log.error("Error in virtual thread {}", threadId, e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Check for errors
    if (!completed) {
      fail("Test timed out - not all virtual threads completed in time");
    }
    
    if (errorCount.get() > 0) {
      fail("Test encountered " + errorCount.get() + " errors during concurrent execution");
    }
    
    log.info("Processed {} unique records and {} duplicate records with {} virtual threads",
        uniqueCount.get(), duplicateCount.get(), threadCount);
    
    strategy.close();
  }
  
  /**
   * Compares performance between virtual threads and platform threads for the given strategy.
   * This method helps identify the performance benefits of using virtual threads for I/O-bound operations.
   *
   * @param strategy The duplicate detection strategy to test
   * @param recordCount The number of records to process in each test
   * @param consumer A consumer that receives performance metrics
   */
  public void compareThreadPerformance(
      final DuplicateDetectionStrategy<Record> strategy,
      final int recordCount,
      final Consumer<PerformanceResult> consumer) throws Exception {
    
    // Test with platform threads
    long platformStart = System.nanoTime();
    runConcurrentTest(strategy, recordCount, 100, platformThreadExecutor);
    long platformDuration = System.nanoTime() - platformStart;
    
    // Reset strategy if needed
    strategy.close();
    
    // Create a new strategy instance of the same type
    DuplicateDetectionStrategy<Record> newStrategy = null;
    if (strategy instanceof HashBasedDuplicateDetectionStrategy) {
      newStrategy = new HashBasedDuplicateDetectionStrategy();
    } else if (strategy instanceof DiskBackedDuplicateDetectionStrategy) {
      newStrategy = new DiskBackedDuplicateDetectionStrategy(temporaryFolder().toFile());
    } else if (strategy instanceof BloomFilterDuplicateDetectionStrategy) {
      newStrategy = new BloomFilterDuplicateDetectionStrategy();
    } else {
      fail("Unknown strategy type: " + strategy.getClass().getName());
    }
    
    // Test with virtual threads
    long virtualStart = System.nanoTime();
    runConcurrentTest(newStrategy, recordCount, 1000, virtualThreadExecutor);
    long virtualDuration = System.nanoTime() - virtualStart;
    
    newStrategy.close();
    
    // Calculate results
    PerformanceResult result = new PerformanceResult(
        Duration.ofNanos(platformDuration),
        Duration.ofNanos(virtualDuration),
        recordCount);
    
    // Report results
    log.info("Performance comparison:\n" +
        "  Platform threads: {} ms ({} records/sec)\n" +
        "  Virtual threads:  {} ms ({} records/sec)\n" +
        "  Improvement factor: {}x",
        result.getPlatformDurationMs(),
        result.getPlatformThroughput(),
        result.getVirtualDurationMs(),
        result.getVirtualThroughput(),
        result.getImprovementFactor());
    
    // Pass results to consumer if provided
    if (consumer != null) {
      consumer.accept(result);
    }
  }
  
  /**
   * Runs a concurrent test with the specified executor service.
   */
  private void runConcurrentTest(
      final DuplicateDetectionStrategy<Record> strategy,
      final int recordCount,
      final int threadCount,
      final ExecutorService executor) throws Exception {
    
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicBoolean failed = new AtomicBoolean(false);
    
    int recordsPerThread = recordCount / threadCount;
    
    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      
      executor.submit(() -> {
        try {
          for (int i = 0; i < recordsPerThread; i++) {
            Record record = buildRecord(
                "group" + (threadId % 10),
                "artifact" + (i % 100),
                "1.0",
                "sources",
                "jar");
            
            strategy.apply(record);
          }
        } catch (Exception e) {
          log.error("Error in thread {}", threadId, e);
          failed.set(true);
        } finally {
          latch.countDown();
        }
      });
    }
    
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    
    if (!completed || failed.get()) {
      fail("Test failed or timed out");
    }
  }
  
  /**
   * Detects thread pinning issues by running the strategy with virtual threads and monitoring for pinning.
   * Thread pinning occurs when a virtual thread is unable to yield its carrier thread during blocking operations,
   * which can significantly impact performance and scalability.
   *
   * @param strategy The duplicate detection strategy to test
   * @return true if thread pinning was detected, false otherwise
   */
  public boolean detectThreadPinning(final DuplicateDetectionStrategy<Record> strategy) throws Exception {
    // Set up thread pinning detection
    // Note: In a real environment, you would use JFR events or the jdk.tracePinnedThreads system property
    // For this test, we'll use a simple approach to detect potential pinning scenarios
    
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(10);
    
    // Run multiple virtual threads that perform operations concurrently
    for (int i = 0; i < 10; i++) {
      final int threadId = i;
      
      Thread.ofVirtual().name("pinning-detection-" + threadId).start(() -> {
        try {
          // Perform operations that might cause pinning
          for (int j = 0; j < 100; j++) {
            Record record = buildRecord("group1", "artifact" + j, "1.0", "sources", "jar");
            
            // Measure time taken for the operation
            long start = System.nanoTime();
            strategy.apply(record);
            long duration = System.nanoTime() - start;
            
            // If an operation takes significantly longer than expected, it might indicate pinning
            // This is a simplified heuristic - real pinning detection would use JFR events
            if (duration > TimeUnit.MILLISECONDS.toNanos(100)) {
              log.warn("Potential thread pinning detected in thread {} - operation took {} ms",
                  threadId, TimeUnit.NANOSECONDS.toMillis(duration));
              pinningDetected.set(true);
            }
          }
        } catch (Exception e) {
          log.error("Error in pinning detection thread {}", threadId, e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    latch.await(30, TimeUnit.SECONDS);
    strategy.close();
    
    return pinningDetected.get();
  }

  /**
   * Builds a test record with the specified attributes.
   */
  protected Record buildRecord(final String g,
                             final String a,
                             final String v,
                             final String c,
                             final String e)
  {
    return new Record(ARTIFACT_ADD, ImmutableMap.of(GROUP_ID, g,
        ARTIFACT_ID, a,
        VERSION, v,
        CLASSIFIER, c,
        FILE_EXTENSION, e));
  }
  
  /**
   * Class to hold performance comparison results.
   */
  public static class PerformanceResult {
    private final Duration platformDuration;
    private final Duration virtualDuration;
    private final int recordCount;
    
    public PerformanceResult(Duration platformDuration, Duration virtualDuration, int recordCount) {
      this.platformDuration = platformDuration;
      this.virtualDuration = virtualDuration;
      this.recordCount = recordCount;
    }
    
    public long getPlatformDurationMs() {
      return platformDuration.toMillis();
    }
    
    public long getVirtualDurationMs() {
      return virtualDuration.toMillis();
    }
    
    public double getPlatformThroughput() {
      return recordCount / (platformDuration.toMillis() / 1000.0);
    }
    
    public double getVirtualThroughput() {
      return recordCount / (virtualDuration.toMillis() / 1000.0);
    }
    
    public double getImprovementFactor() {
      return platformDuration.toNanos() / (double) virtualDuration.toNanos();
    }
  }
}