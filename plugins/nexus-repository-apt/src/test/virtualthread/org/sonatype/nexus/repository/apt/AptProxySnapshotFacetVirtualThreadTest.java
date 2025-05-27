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
package org.sonatype.nexus.repository.apt;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.apt.datastore.internal.proxy.AptProxyFacet;
import org.sonatype.nexus.repository.apt.datastore.internal.proxy.AptProxySnapshotFacet;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem.ContentSpecifier;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AptProxySnapshotFacet} with Java 21 Virtual Threads.
 * 
 * Validates that snapshot operations benefit from the improved concurrency model provided by
 * Virtual Threads, ensuring performance improvements when retrieving multiple snapshot items
 * simultaneously and correct behavior under high concurrency scenarios.
 * 
 * @since 3.60
 */
public class AptProxySnapshotFacetVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int SNAPSHOT_ITEM_COUNT = 100;
  private static final int CONCURRENT_OPERATIONS = 10;
  private static final Duration OPERATION_DELAY = Duration.ofMillis(50);
  
  @Mock
  private Repository repository;
  
  @Mock
  private AptProxyFacet proxyFacet;
  
  private AptProxySnapshotFacet snapshotFacet;
  
  @Before
  public void setup() throws Exception {
    // Skip tests if virtual threads are not supported
    org.junit.Assume.assumeTrue("Virtual Threads not supported in this JVM", isVirtualThreadSupported());
    
    // Setup mocks
    when(repository.facet(AptProxyFacet.class)).thenReturn(proxyFacet);
    
    // Create the facet under test
    snapshotFacet = new AptProxySnapshotFacet();
    snapshotFacet.attach(repository);
    
    // Configure the proxy facet to simulate I/O delay and return mock snapshot items
    doAnswer(invocation -> {
      List<ContentSpecifier> specs = invocation.getArgument(0);
      // Simulate I/O delay
      Thread.sleep(OPERATION_DELAY.toMillis());
      
      // Create mock snapshot items corresponding to the specs
      return specs.stream()
          .map(spec -> createMockSnapshotItem(spec))
          .collect(Collectors.toList());
    }).when(proxyFacet).getSnapshotItems(anyList());
  }
  
  /**
   * Tests that the AptProxySnapshotFacet correctly fetches snapshot items using virtual threads.
   * This verifies basic functionality with the new thread model.
   */
  @Test
  public void testFetchSnapshotItemsWithVirtualThreads() throws Exception {
    // Create a list of content specifiers
    List<ContentSpecifier> specs = createContentSpecifiers(10);
    
    // Execute the fetch operation on a virtual thread
    List<SnapshotItem> items = supplyFromVirtualThread(() -> {
      try {
        // Verify we're running on a virtual thread
        assertCurrentThreadIsVirtual();
        return snapshotFacet.fetchSnapshotItems(specs);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    
    // Verify the results
    assertThat(items, hasSize(10));
    verify(proxyFacet).getSnapshotItems(specs);
  }
  
  /**
   * Tests the performance difference between platform threads and virtual threads
   * when fetching snapshot items. Virtual threads should provide better performance
   * for I/O-bound operations like snapshot fetching.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    // Create a large list of content specifiers
    List<ContentSpecifier> specs = createContentSpecifiers(SNAPSHOT_ITEM_COUNT);
    
    // Split the specs into smaller batches to simulate concurrent operations
    List<List<ContentSpecifier>> batches = splitIntoBatches(specs, CONCURRENT_OPERATIONS);
    
    // Measure time with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try {
        executeConcurrentlyWithPlatformThreads(batches);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Measure time with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try {
        executeConcurrentlyWithVirtualThreads(batches);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Log the results
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be faster for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests that the AptProxySnapshotFacet correctly handles concurrent fetching of
   * multiple snapshot items using virtual threads.
   */
  @Test
  public void testConcurrentSnapshotFetchingWithVirtualThreads() throws Exception {
    // Create a list of content specifiers
    List<ContentSpecifier> specs = createContentSpecifiers(SNAPSHOT_ITEM_COUNT);
    
    // Split the specs into smaller batches to simulate concurrent operations
    List<List<ContentSpecifier>> batches = splitIntoBatches(specs, CONCURRENT_OPERATIONS);
    
    // Execute the batches concurrently using virtual threads
    List<List<SnapshotItem>> results = executeConcurrentlyWithVirtualThreads(batches);
    
    // Verify the results
    assertThat(results, hasSize(batches.size()));
    int totalItems = results.stream().mapToInt(List::size).sum();
    assertThat(totalItems, is(equalTo(SNAPSHOT_ITEM_COUNT)));
    
    // Verify that the proxy facet was called for each batch
    verify(proxyFacet, times(batches.size())).getSnapshotItems(anyList());
  }
  
  /**
   * Tests that the AptProxySnapshotFacet correctly handles errors during concurrent
   * snapshot fetching with virtual threads.
   */
  @Test
  public void testErrorHandlingDuringConcurrentFetching() throws Exception {
    // Configure the proxy facet to throw an exception for certain specs
    doAnswer(invocation -> {
      List<ContentSpecifier> specs = invocation.getArgument(0);
      // Simulate I/O delay
      Thread.sleep(OPERATION_DELAY.toMillis());
      
      // Throw an exception if the first spec has an ID divisible by 3
      if (!specs.isEmpty() && specs.get(0).getId() % 3 == 0) {
        throw new IOException("Simulated network error");
      }
      
      // Otherwise, return mock snapshot items
      return specs.stream()
          .map(spec -> createMockSnapshotItem(spec))
          .collect(Collectors.toList());
    }).when(proxyFacet).getSnapshotItems(anyList());
    
    // Create a list of content specifiers
    List<ContentSpecifier> specs = createContentSpecifiers(SNAPSHOT_ITEM_COUNT);
    
    // Split the specs into smaller batches to simulate concurrent operations
    List<List<ContentSpecifier>> batches = splitIntoBatches(specs, CONCURRENT_OPERATIONS);
    
    // Execute the batches concurrently using virtual threads, catching exceptions
    List<CompletableFuture<List<SnapshotItem>>> futures = new ArrayList<>();
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      for (List<ContentSpecifier> batch : batches) {
        CompletableFuture<List<SnapshotItem>> future = CompletableFuture.supplyAsync(() -> {
          try {
            // Verify we're running on a virtual thread
            assertCurrentThreadIsVirtual();
            return snapshotFacet.fetchSnapshotItems(batch);
          }
          catch (IOException e) {
            // Convert to unchecked exception for CompletableFuture
            throw new RuntimeException(e);
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for all futures to complete, including those that complete exceptionally
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .exceptionally(ex -> null)
          .join();
    }
    
    // Count successful and failed operations
    int successCount = 0;
    int failureCount = 0;
    for (CompletableFuture<List<SnapshotItem>> future : futures) {
      if (future.isCompletedExceptionally()) {
        failureCount++;
      }
      else {
        successCount++;
      }
    }
    
    // Log the results
    log.info("Successful operations: {}", successCount);
    log.info("Failed operations: {}", failureCount);
    
    // Verify that some operations succeeded and some failed
    assertThat("Some operations should succeed", successCount > 0, is(true));
    assertThat("Some operations should fail", failureCount > 0, is(true));
    assertThat("Total operations should match batch count", successCount + failureCount, is(equalTo(batches.size())));
  }
  
  /**
   * Tests that the AptProxySnapshotFacet correctly handles a high number of concurrent
   * operations using virtual threads, which would be impractical with platform threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Skip this test if running in a CI environment or with limited resources
    org.junit.Assume.assumeTrue("Skipping high concurrency test in resource-constrained environment",
        Runtime.getRuntime().availableProcessors() >= 4);
    
    // Create a very large number of content specifiers
    final int HIGH_CONCURRENCY = 1000;
    List<ContentSpecifier> specs = createContentSpecifiers(HIGH_CONCURRENCY);
    
    // Use individual specs for maximum concurrency (one virtual thread per spec)
    List<List<ContentSpecifier>> batches = specs.stream()
        .map(List::of)
        .collect(Collectors.toList());
    
    // Configure the proxy facet for high concurrency testing
    doAnswer(invocation -> {
      List<ContentSpecifier> batchSpecs = invocation.getArgument(0);
      // Shorter delay for high concurrency test
      Thread.sleep(10);
      return batchSpecs.stream()
          .map(spec -> createMockSnapshotItem(spec))
          .collect(Collectors.toList());
    }).when(proxyFacet).getSnapshotItems(anyList());
    
    // Execute with virtual threads
    long startTime = System.currentTimeMillis();
    List<List<SnapshotItem>> results;
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      List<CompletableFuture<List<SnapshotItem>>> futures = batches.stream()
          .map(batch -> CompletableFuture.supplyAsync(() -> {
            try {
              assertCurrentThreadIsVirtual();
              return snapshotFacet.fetchSnapshotItems(batch);
            }
            catch (IOException e) {
              throw new RuntimeException(e);
            }
          }, executor))
          .collect(Collectors.toList());
      
      results = futures.stream()
          .map(CompletableFuture::join)
          .collect(Collectors.toList());
    }
    long duration = System.currentTimeMillis() - startTime;
    
    // Log the results
    log.info("Completed {} concurrent operations in {} ms", HIGH_CONCURRENCY, duration);
    log.info("Average time per operation: {} ms", (double) duration / HIGH_CONCURRENCY);
    
    // Verify the results
    assertThat(results, hasSize(HIGH_CONCURRENCY));
    int totalItems = results.stream().mapToInt(List::size).sum();
    assertThat(totalItems, is(equalTo(HIGH_CONCURRENCY)));
    
    // Verify that the proxy facet was called for each batch
    verify(proxyFacet, times(HIGH_CONCURRENCY)).getSnapshotItems(anyList());
    
    // The total duration should be much less than if we did these sequentially
    // Sequential would be approximately HIGH_CONCURRENCY * 10ms
    long sequentialEstimate = HIGH_CONCURRENCY * 10;
    assertThat("Virtual threads should provide significant concurrency benefits",
        duration, lessThan(sequentialEstimate / 10));
  }
  
  /**
   * Helper method to create a list of mock content specifiers.
   */
  private List<ContentSpecifier> createContentSpecifiers(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> {
          ContentSpecifier spec = Mockito.mock(ContentSpecifier.class);
          when(spec.getId()).thenReturn(i);
          when(spec.toString()).thenReturn("ContentSpecifier-" + i);
          return spec;
        })
        .collect(Collectors.toList());
  }
  
  /**
   * Helper method to create a mock snapshot item for a content specifier.
   */
  private SnapshotItem createMockSnapshotItem(ContentSpecifier spec) {
    SnapshotItem item = Mockito.mock(SnapshotItem.class);
    when(item.getContentSpecifier()).thenReturn(spec);
    when(item.toString()).thenReturn("SnapshotItem-" + spec.getId());
    return item;
  }
  
  /**
   * Helper method to split a list into smaller batches.
   */
  private <T> List<List<T>> splitIntoBatches(List<T> items, int batchCount) {
    List<List<T>> batches = new ArrayList<>();
    int itemsPerBatch = (int) Math.ceil((double) items.size() / batchCount);
    
    for (int i = 0; i < items.size(); i += itemsPerBatch) {
      int end = Math.min(i + itemsPerBatch, items.size());
      batches.add(new ArrayList<>(items.subList(i, end)));
    }
    
    return batches;
  }
  
  /**
   * Helper method to execute batches concurrently using platform threads.
   */
  private List<List<SnapshotItem>> executeConcurrentlyWithPlatformThreads(List<List<ContentSpecifier>> batches) 
      throws Exception 
  {
    List<CompletableFuture<List<SnapshotItem>>> futures = new ArrayList<>();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_OPERATIONS, platformThreadFactory)) {
      for (List<ContentSpecifier> batch : batches) {
        CompletableFuture<List<SnapshotItem>> future = CompletableFuture.supplyAsync(() -> {
          try {
            // Verify we're running on a platform thread
            assertCurrentThreadIsNotVirtual();
            return snapshotFacet.fetchSnapshotItems(batch);
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        }, executor);
        futures.add(future);
      }
      
      return futures.stream()
          .map(CompletableFuture::join)
          .collect(Collectors.toList());
    }
  }
  
  /**
   * Helper method to execute batches concurrently using virtual threads.
   */
  private List<List<SnapshotItem>> executeConcurrentlyWithVirtualThreads(List<List<ContentSpecifier>> batches) 
      throws Exception 
  {
    List<CompletableFuture<List<SnapshotItem>>> futures = new ArrayList<>();
    
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      for (List<ContentSpecifier> batch : batches) {
        CompletableFuture<List<SnapshotItem>> future = CompletableFuture.supplyAsync(() -> {
          try {
            // Verify we're running on a virtual thread
            assertCurrentThreadIsVirtual();
            return snapshotFacet.fetchSnapshotItems(batch);
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        }, executor);
        futures.add(future);
      }
      
      return futures.stream()
          .map(CompletableFuture::join)
          .collect(Collectors.toList());
    }
  }
  
  /**
   * Helper method to measure the execution time of a runnable.
   */
  private long measureExecutionTime(Runnable runnable) {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
}