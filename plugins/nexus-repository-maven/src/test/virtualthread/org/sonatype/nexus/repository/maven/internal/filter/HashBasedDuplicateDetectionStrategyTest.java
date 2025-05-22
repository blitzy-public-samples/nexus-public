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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.index.reader.Record;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Virtual Thread-specific test for {@link HashBasedDuplicateDetectionStrategy}.
 * Tests the behavior of the strategy when executed with Java 21's Virtual Threads.
 */
public class HashBasedDuplicateDetectionStrategyTest
    extends DuplicateDetectionStrategyTestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int RECORDS_PER_THREAD = 100;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @Before
  public void setUp() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @After
  public void tearDown() throws Exception {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    
    virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS);
    platformThreadExecutor.awaitTermination(30, TimeUnit.SECONDS);
  }
  
  @Test
  public void shouldIdentifyDuplicates() throws Exception {
    verifyDuplicateDetection(new HashBasedDuplicateDetectionStrategy());
  }
  
  @Test
  public void shouldHandleConcurrentOperationsWithVirtualThreads() throws Exception {
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger uniqueRecordsCount = new AtomicInteger(0);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    // Create and submit tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Each thread adds a unique record and then tries to add it again
          Record uniqueRecord = buildRecord("group" + threadId, "artifact" + threadId, "1.0", "sources", "jar");
          
          if (strategy.apply(uniqueRecord)) {
            uniqueRecordsCount.incrementAndGet();
          }
          
          // Try to add the same record again - should be detected as duplicate
          if (strategy.apply(uniqueRecord)) {
            // This should not happen - record should be detected as duplicate
            hasErrors.set(true);
          }
        }
        catch (Exception e) {
          hasErrors.set(true);
          log.error("Error in virtual thread {}", threadId, e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", completionLatch.await(30, TimeUnit.SECONDS));
    
    // Verify results
    assertFalse("Errors occurred during concurrent execution", hasErrors.get());
    assertThat(uniqueRecordsCount.get(), is(CONCURRENT_THREADS));
    
    strategy.close();
  }
  
  @Test
  public void shouldHandleHighVolumeWithVirtualThreads() throws Exception {
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger uniqueRecordsCount = new AtomicInteger(0);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    // Create and submit tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Each thread adds multiple records with different versions
          for (int j = 0; j < RECORDS_PER_THREAD; j++) {
            Record uniqueRecord = buildRecord(
                "group" + threadId, 
                "artifact" + threadId, 
                "1." + j, // Different version for each record
                "sources", 
                "jar");
            
            if (strategy.apply(uniqueRecord)) {
              uniqueRecordsCount.incrementAndGet();
            }
          }
        }
        catch (Exception e) {
          hasErrors.set(true);
          log.error("Error in virtual thread {}", threadId, e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", completionLatch.await(60, TimeUnit.SECONDS));
    
    // Verify results
    assertFalse("Errors occurred during concurrent execution", hasErrors.get());
    assertThat(uniqueRecordsCount.get(), is(CONCURRENT_THREADS * RECORDS_PER_THREAD));
    
    strategy.close();
  }
  
  @Test
  public void shouldCompareMemoryUsagePatterns() throws Exception {
    // Get memory MX bean for memory usage monitoring
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    
    // First run with platform threads
    System.gc(); // Request garbage collection before test
    long platformThreadsStartMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    
    runConcurrentTest(platformThreadExecutor);
    
    System.gc(); // Request garbage collection to get accurate memory usage
    long platformThreadsEndMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    long platformThreadsMemoryUsed = platformThreadsEndMemory - platformThreadsStartMemory;
    
    // Then run with virtual threads
    System.gc(); // Request garbage collection before test
    long virtualThreadsStartMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    
    runConcurrentTest(virtualThreadExecutor);
    
    System.gc(); // Request garbage collection to get accurate memory usage
    long virtualThreadsEndMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    long virtualThreadsMemoryUsed = virtualThreadsEndMemory - virtualThreadsStartMemory;
    
    // Log memory usage for both thread types
    log.info("Platform threads memory used: {} bytes", platformThreadsMemoryUsed);
    log.info("Virtual threads memory used: {} bytes", virtualThreadsMemoryUsed);
    
    // Virtual threads should use less memory per thread than platform threads
    // This is a relative comparison, not an absolute requirement
    assertThat("Virtual threads should use less memory than platform threads",
        virtualThreadsMemoryUsed, lessThan(platformThreadsMemoryUsed));
  }
  
  @Test
  public void shouldNotCauseThreadPinning() throws Exception {
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    int numThreads = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch pinnedLatch = new CountDownLatch(numThreads);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a list to hold all the futures
    List<Future<?>> futures = new ArrayList<>();
    
    // Submit tasks that will detect pinning
    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Create a unique record for this thread
          Record record = buildRecord("pinning-test-group" + threadId, 
              "pinning-test-artifact" + threadId, 
              "1.0", 
              "sources", 
              "jar");
          
          // Measure time taken for hash computation
          long startTime = System.nanoTime();
          boolean result = strategy.apply(record);
          long endTime = System.nanoTime();
          long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
          
          // If operation takes too long, it might indicate thread pinning
          // This is a simple heuristic - in a real scenario, more sophisticated detection would be used
          if (duration > 100) { // 100ms threshold is arbitrary
            log.warn("Possible thread pinning detected in thread {} - operation took {} ms", threadId, duration);
            pinningDetected.set(true);
          }
          
          assertTrue("Record should be unique", result);
        }
        catch (Exception e) {
          log.error("Error in thread pinning test for thread {}", threadId, e);
        }
        finally {
          pinnedLatch.countDown();
        }
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", pinnedLatch.await(30, TimeUnit.SECONDS));
    
    // Verify no thread pinning was detected
    assertFalse("Thread pinning detected during hash computation", pinningDetected.get());
    
    // Cancel any remaining futures and close the strategy
    for (Future<?> future : futures) {
      future.cancel(true);
    }
    
    strategy.close();
  }
  
  private void runConcurrentTest(ExecutorService executor) throws Exception {
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Create and submit tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Each thread adds a unique record
          Record uniqueRecord = buildRecord("memoryTest-group" + threadId, 
              "memoryTest-artifact" + threadId, 
              "1.0", 
              "sources", 
              "jar");
          
          strategy.apply(uniqueRecord);
        }
        catch (Exception e) {
          log.error("Error in memory test thread {}", threadId, e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", completionLatch.await(30, TimeUnit.SECONDS));
    
    strategy.close();
  }
  
  private Record buildRecord(final String g,
                             final String a,
                             final String v,
                             final String c,
                             final String e)
  {
    return new Record(org.apache.maven.index.reader.Record.Type.ARTIFACT_ADD, 
        com.google.common.collect.ImmutableMap.of(
            org.apache.maven.index.reader.Record.GROUP_ID, g,
            org.apache.maven.index.reader.Record.ARTIFACT_ID, a,
            org.apache.maven.index.reader.Record.VERSION, v,
            org.apache.maven.index.reader.Record.CLASSIFIER, c,
            org.apache.maven.index.reader.Record.FILE_EXTENSION, e));
  }
}