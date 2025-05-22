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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.apt.datastore.internal.proxy.AptProxyFacet;
import org.sonatype.nexus.repository.apt.datastore.internal.proxy.AptProxySnapshotFacet;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem.ContentSpecifier;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem.Role;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AptProxySnapshotFacet} using virtual threads.
 * 
 * @since 3.60
 */
public class AptProxySnapshotFacetVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int ITEM_COUNT = 10;
  private static final int CONCURRENT_REQUESTS = 5;
  private static final long SIMULATED_IO_DELAY_MS = 50;
  
  @Mock
  private Repository repository;
  
  @Mock
  private AptProxyFacet proxyFacet;
  
  private AptProxySnapshotFacet snapshotFacet;
  
  @BeforeEach
  public void setUp() throws Exception {
    // Skip tests if virtual threads are not supported
    assumeVirtualThreadSupported();
    
    // Set up the snapshot facet with mocked dependencies
    snapshotFacet = new AptProxySnapshotFacet();
    when(repository.facet(AptProxyFacet.class)).thenReturn(proxyFacet);
    snapshotFacet.attach(repository);
  }
  
  /**
   * Tests that the snapshot facet can fetch items using virtual threads.
   */
  @Test
  public void testFetchSnapshotItemsWithVirtualThreads() throws Exception {
    // Set up mock to return snapshot items
    List<SnapshotItem> expectedItems = createMockSnapshotItems(ITEM_COUNT);
    when(proxyFacet.getSnapshotItems(anyList())).thenReturn(expectedItems);
    
    // Execute the fetchSnapshotItems method on a virtual thread
    List<ContentSpecifier> specs = createContentSpecifiers(ITEM_COUNT);
    List<SnapshotItem> result = callVirtual(() -> snapshotFacet.fetchSnapshotItems(specs));
    
    // Verify the result
    assertThat(result, hasSize(ITEM_COUNT));
    assertThat(result, equalTo(expectedItems));
    verify(proxyFacet).getSnapshotItems(specs);
  }
  
  /**
   * Tests that the snapshot facet can fetch items concurrently using virtual threads.
   */
  @Test
  public void testConcurrentFetchingWithVirtualThreads() throws Exception {
    // Set up mock to return snapshot items with a delay to simulate I/O
    doAnswer(invocation -> {
      List<ContentSpecifier> specs = invocation.getArgument(0);
      Thread.sleep(SIMULATED_IO_DELAY_MS); // Simulate I/O delay
      return specs.stream()
          .map(spec -> createSnapshotItem(spec))
          .collect(Collectors.toList());
    }).when(proxyFacet).getSnapshotItems(anyList());
    
    // Create multiple sets of content specifiers
    List<List<ContentSpecifier>> specSets = IntStream.range(0, CONCURRENT_REQUESTS)
        .mapToObj(i -> createContentSpecifiers(ITEM_COUNT))
        .collect(Collectors.toList());
    
    // Execute fetchSnapshotItems concurrently using virtual threads
    ExecutorService executor = newVirtualThreadExecutor("snapshot-test-");
    try {
      List<Future<List<SnapshotItem>>> futures = specSets.stream()
          .map(specs -> executor.submit(() -> snapshotFacet.fetchSnapshotItems(specs)))
          .collect(Collectors.toList());
      
      // Wait for all futures to complete and collect results
      List<List<SnapshotItem>> results = futures.stream()
          .map(future -> {
            try {
              return future.get(10, TimeUnit.SECONDS);
            }
            catch (Exception e) {
              throw new RuntimeException(e);
            }
          })
          .collect(Collectors.toList());
      
      // Verify results
      assertThat(results, hasSize(CONCURRENT_REQUESTS));
      results.forEach(items -> assertThat(items, hasSize(ITEM_COUNT)));
    }
    finally {
      executor.shutdown();
    }
    
    // Verify that getSnapshotItems was called the expected number of times
    verify(proxyFacet, times(CONCURRENT_REQUESTS)).getSnapshotItems(any());
  }
  
  /**
   * Tests that virtual threads provide better performance than platform threads
   * when fetching snapshot items concurrently.
   */
  @Test
  public void testVirtualThreadPerformanceComparison() throws Exception {
    // Set up mock to return snapshot items with a delay to simulate I/O
    AtomicInteger concurrentExecutions = new AtomicInteger(0);
    AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);
    
    doAnswer(invocation -> {
      List<ContentSpecifier> specs = invocation.getArgument(0);
      // Track concurrent executions
      int current = concurrentExecutions.incrementAndGet();
      maxConcurrentExecutions.updateAndGet(max -> Math.max(max, current));
      
      try {
        Thread.sleep(SIMULATED_IO_DELAY_MS); // Simulate I/O delay
        return specs.stream()
            .map(spec -> createSnapshotItem(spec))
            .collect(Collectors.toList());
      }
      finally {
        concurrentExecutions.decrementAndGet();
      }
    }).when(proxyFacet).getSnapshotItems(anyList());
    
    // Create a large number of content specifier sets
    int requestCount = 50;
    List<List<ContentSpecifier>> specSets = IntStream.range(0, requestCount)
        .mapToObj(i -> createContentSpecifiers(5))
        .collect(Collectors.toList());
    
    // Measure execution time with platform threads (limited concurrency)
    long platformThreadTime = measureExecutionTimeWithThreads(
        specSets, 
        Executors.newFixedThreadPool(10), // Limited to 10 platform threads
        "Platform Thread Test");
    
    // Reset counters
    maxConcurrentExecutions.set(0);
    Mockito.clearInvocations(proxyFacet);
    
    // Measure execution time with virtual threads (high concurrency)
    long virtualThreadTime = measureExecutionTimeWithThreads(
        specSets, 
        newVirtualThreadExecutor("virtual-snapshot-test-"),
        "Virtual Thread Test");
    
    // Verify that virtual threads achieved higher concurrency
    assertThat("Virtual threads should achieve higher concurrency",
        maxConcurrentExecutions.get(), greaterThan(10));
    
    // Verify that virtual threads were faster
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    assertThat("Virtual threads should be faster than platform threads",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests that the snapshot facet correctly aggregates results when fetching items concurrently.
   */
  @Test
  public void testConcurrentAggregation() throws Exception {
    // Set up a CountDownLatch to synchronize threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    // Set up mock to return snapshot items with synchronized execution
    doAnswer(invocation -> {
      List<ContentSpecifier> specs = invocation.getArgument(0);
      startLatch.await(); // Wait for all threads to be ready
      Thread.sleep(SIMULATED_IO_DELAY_MS); // Simulate I/O delay
      List<SnapshotItem> items = specs.stream()
          .map(spec -> createSnapshotItem(spec))
          .collect(Collectors.toList());
      completionLatch.countDown();
      return items;
    }).when(proxyFacet).getSnapshotItems(anyList());
    
    // Create multiple sets of content specifiers with overlapping items
    List<ContentSpecifier> commonSpecs = createContentSpecifiers(5);
    List<List<ContentSpecifier>> specSets = IntStream.range(0, CONCURRENT_REQUESTS)
        .mapToObj(i -> {
          List<ContentSpecifier> uniqueSpecs = createContentSpecifiers(5, "unique-" + i + "-");
          List<ContentSpecifier> combined = new ArrayList<>(commonSpecs);
          combined.addAll(uniqueSpecs);
          return combined;
        })
        .collect(Collectors.toList());
    
    // Execute fetchSnapshotItems concurrently using virtual threads
    ExecutorService executor = newVirtualThreadExecutor("snapshot-aggregation-test-");
    try {
      List<Future<List<SnapshotItem>>> futures = specSets.stream()
          .map(specs -> executor.submit(() -> snapshotFacet.fetchSnapshotItems(specs)))
          .collect(Collectors.toList());
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all operations to complete
      completionLatch.await(10, TimeUnit.SECONDS);
      
      // Collect results
      List<List<SnapshotItem>> results = futures.stream()
          .map(future -> {
            try {
              return future.get(5, TimeUnit.SECONDS);
            }
            catch (Exception e) {
              throw new RuntimeException(e);
            }
          })
          .collect(Collectors.toList());
      
      // Verify that each result has the expected number of items
      results.forEach(items -> assertThat(items, hasSize(10))); // 5 common + 5 unique
      
      // Verify that getSnapshotItems was called the expected number of times
      verify(proxyFacet, times(CONCURRENT_REQUESTS)).getSnapshotItems(any());
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that the callable for fetching snapshot items completes within an expected time frame.
   */
  @Test
  public void testFetchSnapshotItemsCompletesWithinExpectedTime() throws Exception {
    // Set up mock to return snapshot items with a fixed delay
    doAnswer(invocation -> {
      Thread.sleep(SIMULATED_IO_DELAY_MS);
      return createMockSnapshotItems(ITEM_COUNT);
    }).when(proxyFacet).getSnapshotItems(anyList());
    
    // Create a callable that fetches snapshot items
    List<ContentSpecifier> specs = createContentSpecifiers(ITEM_COUNT);
    Callable<List<SnapshotItem>> fetchCallable = () -> snapshotFacet.fetchSnapshotItems(specs);
    
    // Verify that the callable completes within the expected time
    Duration expectedDuration = Duration.ofMillis(SIMULATED_IO_DELAY_MS * 2); // Allow some buffer
    assertThat(fetchCallable, VirtualThreadMatchers.executesWithin(expectedDuration));
  }
  
  /**
   * Tests that the thread used to fetch snapshot items is a virtual thread.
   */
  @Test
  public void testFetchSnapshotItemsUsesVirtualThread() throws Exception {
    // Set up mock to capture and verify the thread type
    doAnswer(invocation -> {
      // Verify that the current thread is a virtual thread
      Thread currentThread = Thread.currentThread();
      assertThat(currentThread, VirtualThreadMatchers.isVirtualThread());
      return createMockSnapshotItems(ITEM_COUNT);
    }).when(proxyFacet).getSnapshotItems(anyList());
    
    // Execute fetchSnapshotItems on a virtual thread
    List<ContentSpecifier> specs = createContentSpecifiers(ITEM_COUNT);
    runVirtual(() -> {
      try {
        snapshotFacet.fetchSnapshotItems(specs);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    
    // Verify that getSnapshotItems was called
    verify(proxyFacet).getSnapshotItems(specs);
  }
  
  /**
   * Helper method to measure execution time with different thread types.
   */
  private long measureExecutionTimeWithThreads(
      List<List<ContentSpecifier>> specSets,
      ExecutorService executor,
      String testName) throws Exception 
  {
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit all tasks to the executor
      List<Future<List<SnapshotItem>>> futures = specSets.stream()
          .map(specs -> executor.submit(() -> snapshotFacet.fetchSnapshotItems(specs)))
          .collect(Collectors.toList());
      
      // Wait for all futures to complete
      for (Future<List<SnapshotItem>> future : futures) {
        List<SnapshotItem> items = future.get(30, TimeUnit.SECONDS);
        assertThat(items, notNullValue());
      }
      
      return System.currentTimeMillis() - startTime;
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Creates a list of mock snapshot items.
   */
  private List<SnapshotItem> createMockSnapshotItems(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> {
          ContentSpecifier spec = new ContentSpecifier(Role.RELEASE_INDEX, "path/to/item-" + i);
          return createSnapshotItem(spec);
        })
        .collect(Collectors.toList());
  }
  
  /**
   * Creates a snapshot item for the given content specifier.
   */
  private SnapshotItem createSnapshotItem(ContentSpecifier spec) {
    byte[] bytes = ("content for " + spec.path).getBytes();
    BytesPayload payload = new BytesPayload(bytes, spec.role.getMimeType());
    Content content = new Content(payload);
    return new SnapshotItem(spec, content);
  }
  
  /**
   * Creates a list of content specifiers.
   */
  private List<ContentSpecifier> createContentSpecifiers(int count) {
    return createContentSpecifiers(count, "");
  }
  
  /**
   * Creates a list of content specifiers with a prefix.
   */
  private List<ContentSpecifier> createContentSpecifiers(int count, String prefix) {
    return IntStream.range(0, count)
        .mapToObj(i -> new ContentSpecifier(Role.RELEASE_INDEX, prefix + "path/to/item-" + i))
        .collect(Collectors.toList());
  }
}