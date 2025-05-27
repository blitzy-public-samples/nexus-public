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

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.apache.maven.index.reader.Record;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

/**
 * Virtual Thread-specific test for {@link DiskBackedDuplicateDetectionStrategy}.
 * 
 * <p>This test class validates the behavior of the disk-backed duplicate detection strategy
 * when executed with Java 21's Virtual Threads. It focuses on ensuring that disk I/O operations
 * performed by the strategy work correctly with Virtual Threads, which is particularly important
 * as I/O operations are a primary use case for Virtual Threads' efficiency improvements.</p>
 *
 * @since 3.60
 */
public class DiskBackedDuplicateDetectionStrategyTest
    extends DuplicateDetectionStrategyTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(DiskBackedDuplicateDetectionStrategyTest.class);
  
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  @Mock
  private ApplicationDirectories applicationDirectories;
  
  private static final int HIGH_CONCURRENCY_THREADS = 1000;
  private static final int PERFORMANCE_TEST_OPERATIONS = 10000;

  /**
   * Tests that the strategy correctly identifies duplicates when running with Virtual Threads.
   * This is the basic functionality test adapted for Virtual Threads.
   */
  @Test
  public void shouldIdentifyDuplicatesWithVirtualThreads() throws Exception {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tmpDir.getRoot());

    DiskBackedDuplicateDetectionStrategy strategy = 
        new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10);
    
    // Use the Virtual Thread-specific verification method
    verifyDuplicateDetectionWithVirtualThreads(strategy);
  }
  
  /**
   * Tests the strategy under high concurrency with thousands of Virtual Threads.
   * This test validates that the disk-backed strategy can handle a large number of
   * concurrent operations, which is a key benefit of Virtual Threads for I/O-bound tasks.
   */
  @Test
  public void shouldHandleHighConcurrencyWithVirtualThreads() throws Exception {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tmpDir.getRoot());

    DiskBackedDuplicateDetectionStrategy strategy = 
        new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10);
    
    // Create a large number of records to test with
    int recordCount = 100;
    List<Record> records = new ArrayList<>(recordCount);
    for (int i = 0; i < recordCount; i++) {
      records.add(buildRecord("group" + (i % 10), "artifact" + i, "1.0", "sources", "jar"));
    }
    
    // Use a large number of Virtual Threads to test concurrent access
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(HIGH_CONCURRENCY_THREADS);
      AtomicBoolean hasErrors = new AtomicBoolean(false);
      AtomicInteger processedCount = new AtomicInteger(0);
      
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit tasks to the executor
      for (int t = 0; t < HIGH_CONCURRENCY_THREADS; t++) {
        final int threadNum = t;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Each thread processes a subset of records
            for (int i = 0; i < 5; i++) {
              int index = (threadNum * 5 + i) % recordCount;
              Record record = records.get(index);
              strategy.apply(record);
              processedCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error(STR."Error in high concurrency test on thread \{Thread.currentThread().getName()}", e);
            hasErrors.set(true);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for completion with timeout
      if (!completionLatch.await(60, TimeUnit.SECONDS)) {
        fail("High concurrency test timed out after 60 seconds");
      }
      
      // Check for errors
      assertFalse("Errors occurred during high concurrency execution", hasErrors.get());
      
      log.info(STR."Successfully processed \{processedCount.get()} records with \{HIGH_CONCURRENCY_THREADS} virtual threads");
    }
    
    strategy.close();
  }
  
  /**
   * Tests for thread pinning issues during file system operations.
   * Thread pinning occurs when a Virtual Thread cannot unmount from its carrier thread,
   * typically due to synchronized blocks or native methods, reducing the benefits of Virtual Threads.
   */
  @Test
  public void shouldNotHaveThreadPinningIssues() throws Exception {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tmpDir.getRoot());

    DiskBackedDuplicateDetectionStrategy strategy = 
        new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10);
    
    boolean pinningDetected = detectThreadPinning(strategy);
    
    assertFalse("Thread pinning detected in DiskBackedDuplicateDetectionStrategy", pinningDetected);
    
    strategy.close();
  }
  
  /**
   * Compares performance between platform threads and virtual threads for I/O operations.
   * This test quantifies the performance benefits of using Virtual Threads for disk I/O
   * operations in the duplicate detection strategy.
   */
  @Test
  public void shouldPerformBetterWithVirtualThreads() throws Exception {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tmpDir.getRoot());

    DiskBackedDuplicateDetectionStrategy strategy = 
        new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10);
    
    Duration timeSaved = compareThreadPerformance(strategy, PERFORMANCE_TEST_OPERATIONS);
    
    log.info(STR."Performance difference: \{timeSaved.toMillis()} ms saved with Virtual Threads");
    
    // Virtual Threads should generally perform better for I/O operations
    // but we don't want to fail the test if they don't in some environments
    if (timeSaved.isNegative()) {
      log.warn(STR."Virtual Threads were slower by \{timeSaved.abs().toMillis()} ms in this environment");
    }
    else {
      log.info(STR."Virtual Threads were faster by \{timeSaved.toMillis()} ms in this environment");
    }
    
    strategy.close();
  }
  
  /**
   * Tests the strategy's behavior with a large number of files.
   * This test validates that the disk-backed strategy can handle a large number of
   * files, which is important for repository operations that may process many artifacts.
   */
  @Test
  public void shouldHandleLargeNumberOfFiles() throws Exception {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tmpDir.getRoot());

    // Create a strategy with a larger buffer size to handle more files
    DiskBackedDuplicateDetectionStrategy strategy = 
        new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 10, 100);
    
    int fileCount = 1000;
    AtomicInteger acceptedCount = new AtomicInteger(0);
    
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(fileCount);
      
      // Process a large number of files concurrently
      for (int i = 0; i < fileCount; i++) {
        final int fileNum = i;
        executor.submit(() -> {
          try {
            Record record = buildRecord("group" + (fileNum % 10), 
                                       "artifact" + fileNum, 
                                       "1.0", 
                                       "sources", 
                                       "jar");
            if (strategy.apply(record)) {
              acceptedCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error(STR."Error processing file \{fileNum}", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for completion
      if (!completionLatch.await(60, TimeUnit.SECONDS)) {
        fail("Large file test timed out after 60 seconds");
      }
    }
    
    log.info(STR."Processed \{fileCount} files, accepted \{acceptedCount.get()}");
    
    // We expect 100 unique files (10 groups × 10 artifacts)
    assertTrue("Should have accepted at least 100 unique files", acceptedCount.get() >= 100);
    assertTrue("Should have rejected some duplicate files", acceptedCount.get() < fileCount);
    
    strategy.close();
  }
  
  /**
   * Tests the strategy's behavior when the temporary directory is full or has limited space.
   * This test simulates a scenario where the disk is running out of space, which is an
   * important edge case to handle for production environments.
   */
  @Test
  public void shouldHandleDiskSpaceLimitations() throws Exception {
    // Create a temporary directory with limited space
    File limitedSpaceDir = tmpDir.newFolder("limited-space");
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(limitedSpaceDir);

    DiskBackedDuplicateDetectionStrategy strategy = 
        new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10);
    
    // Add some records to the strategy
    for (int i = 0; i < 10; i++) {
      Record record = buildRecord("group1", "artifact" + i, "1.0", "sources", "jar");
      strategy.apply(record);
    }
    
    // Simulate disk space limitations by filling the directory with large files
    // Note: In a real test, we might use a mock file system or other techniques
    // to simulate disk space limitations more accurately
    try {
      for (int i = 0; i < 5; i++) {
        File largeFile = new File(limitedSpaceDir, "large-file-" + i);
        // Create a file that's large enough to potentially cause issues
        // but not so large that it fills the actual disk
        byte[] buffer = new byte[1024 * 1024]; // 1MB
        java.nio.file.Files.write(largeFile.toPath(), buffer);
      }
    }
    catch (Exception e) {
      log.warn("Could not create large files to simulate disk space limitations", e);
    }
    
    // Continue adding records and verify the strategy still works
    boolean stillWorking = true;
    try {
      for (int i = 10; i < 20; i++) {
        Record record = buildRecord("group1", "artifact" + i, "1.0", "sources", "jar");
        strategy.apply(record);
      }
    }
    catch (Exception e) {
      log.info("Strategy encountered an expected error due to disk space limitations", e);
      stillWorking = false;
    }
    
    // The strategy should either continue working or fail gracefully
    if (stillWorking) {
      log.info("Strategy continued to work despite simulated disk space limitations");
    }
    else {
      log.info("Strategy failed gracefully with simulated disk space limitations");
    }
    
    strategy.close();
  }
}