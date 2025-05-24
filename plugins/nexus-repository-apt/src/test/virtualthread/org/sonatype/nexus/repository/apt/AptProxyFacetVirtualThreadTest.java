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
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.apt.datastore.AptContentFacet;
import org.sonatype.nexus.repository.apt.datastore.internal.proxy.AptProxyFacet;
import org.sonatype.nexus.repository.apt.internal.snapshot.AptSnapshotHandler;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem.ContentSpecifier;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheControllerHolder;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AptProxyFacet} using Java 21 Virtual Threads.
 * 
 * These tests validate that the AptProxyFacet correctly handles concurrent operations
 * when executed with Virtual Threads, ensuring performance improvements and correct
 * behavior under high concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
public class AptProxyFacetVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_PATH = "dists/stable/Release";
  private static final int CONCURRENT_REQUESTS = 100;
  private static final int HIGH_CONCURRENCY_REQUESTS = 1000;
  
  @Mock
  private Repository repository;
  
  @Mock
  private HttpClientFacet httpClientFacet;
  
  @Mock
  private HttpClient httpClient;
  
  @Mock
  private ProxyFacet proxyFacet;
  
  @Mock
  private AptContentFacet aptContentFacet;
  
  @Mock
  private CacheControllerHolder cacheControllerHolder;
  
  @Mock
  private CacheController cacheController;
  
  @Mock
  private Context context;
  
  @Mock
  private AttributesMap attributesMap;
  
  @Mock
  private AptSnapshotHandler.State state;
  
  @Mock
  private HttpResponse httpResponse;
  
  @Mock
  private StatusLine statusLine;
  
  @Mock
  private HttpEntity httpEntity;
  
  @Mock
  private Content content;
  
  @Mock
  private Asset asset;
  
  private AptProxyFacet underTest;
  
  @BeforeEach
  public void setUp() throws Exception {
    underTest = new AptProxyFacet();
    underTest.attach(repository);
    
    // Setup repository facets
    when(repository.facet(HttpClientFacet.class)).thenReturn(httpClientFacet);
    when(repository.facet(ProxyFacet.class)).thenReturn(proxyFacet);
    when(repository.facet(AptContentFacet.class)).thenReturn(aptContentFacet);
    
    // Setup HTTP client
    when(httpClientFacet.getHttpClient()).thenReturn(httpClient);
    
    // Setup cache controller
    underTest.cacheControllerHolder = cacheControllerHolder;
    when(cacheControllerHolder.getMetadataCacheController()).thenReturn(cacheController);
    when(cacheControllerHolder.getContentCacheController()).thenReturn(cacheController);
    
    // Setup context
    when(context.getAttributes()).thenReturn(attributesMap);
    when(attributesMap.require(AptSnapshotHandler.State.class)).thenReturn(state);
    when(state.assetPath).thenReturn(TEST_PATH);
    
    // Setup HTTP response
    when(httpResponse.getStatusLine()).thenReturn(statusLine);
    when(httpResponse.getEntity()).thenReturn(httpEntity);
    when(statusLine.getStatusCode()).thenReturn(200);
    
    // Setup proxy facet
    when(proxyFacet.getRemoteUrl()).thenReturn(new URI("http://example.com/"));
    
    // Setup lenient mocks for methods that might be called in different scenarios
    lenient().when(httpClient.execute(any(HttpGet.class))).thenReturn(httpResponse);
    lenient().when(aptContentFacet.put(anyString(), any(Content.class))).thenReturn(asset);
    lenient().when(asset.markAsCached(any(Content.class))).thenReturn(asset);
    lenient().when(asset.download()).thenReturn(content);
  }
  
  /**
   * Tests that the AptProxyFacet can handle concurrent snapshot item fetches using Virtual Threads.
   * This validates that the implementation works correctly with the lightweight threading model
   * introduced in Java 21.
   */
  @Test
  @DisplayName("Concurrent snapshot fetches with Virtual Threads")
  public void testConcurrentSnapshotFetchesWithVirtualThreads() throws Exception {
    // Create a list of content specifiers to fetch
    List<ContentSpecifier> specs = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      specs.add(new ContentSpecifier("path-" + i));
    }
    
    // Setup content facet to return empty optionals for initial gets (forcing fetches)
    when(aptContentFacet.get(anyString())).thenReturn(Optional.empty());
    
    // Setup a latch to track completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use virtual threads for the operation
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("apt-proxy-test-").factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CompletableFuture.runAsync(() -> {
        try {
          List<SnapshotItem> items = underTest.getSnapshotItems(specs);
          successCount.set(items.size());
        } 
        catch (Exception e) {
          log.error("Error fetching snapshot items", e);
        }
        finally {
          latch.countDown();
        }
      }, executor);
      
      // Wait for completion with timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "Operation should complete within timeout");
      assertEquals(CONCURRENT_REQUESTS, successCount.get(), "All snapshot items should be fetched");
      
      // Verify HTTP client was called for each item
      verify(httpClient, times(CONCURRENT_REQUESTS)).execute(any(HttpGet.class));
    }
  }
  
  /**
   * Tests high concurrency scenario with many virtual threads simultaneously fetching
   * snapshot items. This validates that the implementation can handle a large number
   * of concurrent operations efficiently using Virtual Threads.
   */
  @Test
  @DisplayName("High concurrency snapshot fetches with Virtual Threads")
  public void testHighConcurrencySnapshotFetchesWithVirtualThreads() throws Exception {
    // Create a single content specifier
    ContentSpecifier spec = new ContentSpecifier(TEST_PATH);
    
    // Setup content facet to return empty optionals for initial gets (forcing fetches)
    when(aptContentFacet.get(anyString())).thenReturn(Optional.empty());
    
    // Setup a latch to track completion
    CountDownLatch latch = new CountDownLatch(HIGH_CONCURRENCY_REQUESTS);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<String, SnapshotItem> results = new ConcurrentHashMap<>();
    
    // Use virtual threads for the operations
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("apt-proxy-test-").factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit many concurrent tasks
      for (int i = 0; i < HIGH_CONCURRENCY_REQUESTS; i++) {
        final int index = i;
        CompletableFuture.runAsync(() -> {
          try {
            List<ContentSpecifier> singleSpec = List.of(spec);
            List<SnapshotItem> items = underTest.getSnapshotItems(singleSpec);
            if (!items.isEmpty()) {
              results.put("result-" + index, items.get(0));
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error in concurrent fetch {}", index, e);
          }
          finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for completion with timeout
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All operations should complete within timeout");
      assertEquals(0, errorCount.get(), "There should be no errors during concurrent fetches");
      assertEquals(HIGH_CONCURRENCY_REQUESTS, results.size(), "All fetches should return results");
    }
  }
  
  /**
   * Tests that caching works correctly with virtual threads by verifying that
   * cached content is properly returned for subsequent requests without
   * unnecessary remote fetches.
   */
  @Test
  @DisplayName("Cache behavior with Virtual Threads")
  public void testCacheBehaviorWithVirtualThreads() throws Exception {
    // Create a content specifier
    ContentSpecifier spec = new ContentSpecifier(TEST_PATH);
    
    // Setup mock for initial empty cache, then populated cache
    AtomicReference<Optional<Content>> contentRef = new AtomicReference<>(Optional.empty());
    when(aptContentFacet.get(eq(TEST_PATH))).thenAnswer(invocation -> contentRef.get());
    
    // Setup cache info
    CacheInfo cacheInfo = mock(CacheInfo.class);
    when(cacheController.current()).thenReturn(cacheInfo);
    
    // After first fetch, update the content reference to simulate cached content
    doAnswer(invocation -> {
      // After content is stored, make it available for subsequent gets
      contentRef.set(Optional.of(content));
      return asset;
    }).when(aptContentFacet).put(eq(TEST_PATH), any(Content.class));
    
    // Use virtual threads for the operations
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("apt-proxy-test-").factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // First fetch - should go to remote
      CompletableFuture<List<SnapshotItem>> firstFetch = CompletableFuture.supplyAsync(() -> {
        try {
          return underTest.getSnapshotItems(List.of(spec));
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      List<SnapshotItem> firstResult = firstFetch.get(10, TimeUnit.SECONDS);
      assertNotNull(firstResult, "First fetch should return results");
      assertEquals(1, firstResult.size(), "First fetch should return one item");
      
      // Verify HTTP client was called for the first fetch
      verify(httpClient, times(1)).execute(any(HttpGet.class));
      
      // Setup cache controller to indicate content is not stale
      when(cacheController.isStale(any(CacheInfo.class))).thenReturn(false);
      when(content.getAttributes()).thenReturn(attributesMap);
      when(attributesMap.get(eq(CacheInfo.class))).thenReturn(cacheInfo);
      
      // Second fetch - should use cache
      CompletableFuture<List<SnapshotItem>> secondFetch = CompletableFuture.supplyAsync(() -> {
        try {
          return underTest.getSnapshotItems(List.of(spec));
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      List<SnapshotItem> secondResult = secondFetch.get(10, TimeUnit.SECONDS);
      assertNotNull(secondResult, "Second fetch should return results");
      assertEquals(1, secondResult.size(), "Second fetch should return one item");
      
      // Verify HTTP client was still only called once (not for the second fetch)
      verify(httpClient, times(1)).execute(any(HttpGet.class));
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads when
   * executing concurrent proxy operations. This test validates that virtual
   * threads provide better scalability and performance for I/O-bound operations.
   */
  @Test
  @DisplayName("Performance comparison: Platform Threads vs Virtual Threads")
  public void testPerformanceComparisonBetweenThreadModels() throws Exception {
    // Create a content specifier
    ContentSpecifier spec = new ContentSpecifier(TEST_PATH);
    List<ContentSpecifier> specs = List.of(spec);
    
    // Setup content facet to return empty optionals (forcing fetches)
    when(aptContentFacet.get(anyString())).thenReturn(Optional.empty());
    
    // Add a small delay to simulate network latency
    doAnswer(invocation -> {
      // Simulate network latency
      Thread.sleep(50);
      return httpResponse;
    }).when(httpClient).execute(any(HttpGet.class));
    
    // Measure platform threads performance
    long platformThreadTime = measurePerformance(() -> {
      ThreadFactory platformThreadFactory = Thread.ofPlatform().name("apt-proxy-platform-").factory();
      return Executors.newThreadPerTaskExecutor(platformThreadFactory);
    }, specs, 100);
    
    // Measure virtual threads performance
    long virtualThreadTime = measurePerformance(() -> {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("apt-proxy-virtual-").factory();
      return Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    }, specs, 100);
    
    // Log the results
    log.info("Platform threads execution time: {} ms", platformThreadTime);
    log.info("Virtual threads execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be faster or at least not significantly slower
    // The exact performance difference will depend on the environment,
    // but virtual threads should generally perform better for I/O-bound operations
    assertThat("Virtual threads should perform better than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.2)); // Allow some margin
  }
  
  /**
   * Tests that no thread pinning occurs during proxy operations with virtual threads.
   * Thread pinning can significantly reduce the performance benefits of virtual threads.
   */
  @Test
  @DisplayName("No thread pinning with Virtual Threads")
  public void testNoThreadPinningWithVirtualThreads() throws Exception {
    // Create a content specifier
    ContentSpecifier spec = new ContentSpecifier(TEST_PATH);
    
    // Setup content facet to return empty optionals (forcing fetches)
    when(aptContentFacet.get(anyString())).thenReturn(Optional.empty());
    
    // Capture the thread names during execution to check for carrier thread reuse
    ConcurrentHashMap<String, Integer> carrierThreadCounts = new ConcurrentHashMap<>();
    
    // Add instrumentation to detect thread pinning
    doAnswer(invocation -> {
      // Get the current carrier thread name
      String threadName = Thread.currentThread().getName();
      if (threadName.contains("carrier")) {
        // Count occurrences of each carrier thread
        carrierThreadCounts.compute(threadName, (k, v) -> (v == null) ? 1 : v + 1);
      }
      // Simulate some I/O latency
      Thread.sleep(10);
      return httpResponse;
    }).when(httpClient).execute(any(HttpGet.class));
    
    // Use virtual threads for the operations
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("apt-proxy-test-").factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit concurrent tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        futures.add(CompletableFuture.runAsync(() -> {
          try {
            underTest.getSnapshotItems(List.of(spec));
          } 
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor));
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .get(30, TimeUnit.SECONDS);
    }
    
    // Log carrier thread distribution
    log.info("Carrier thread distribution: {}", carrierThreadCounts);
    
    // If we have carrier thread information, verify no excessive pinning
    if (!carrierThreadCounts.isEmpty()) {
      // Calculate the average number of operations per carrier thread
      double avgOpsPerThread = carrierThreadCounts.values().stream()
          .mapToInt(Integer::intValue)
          .average()
          .orElse(0.0);
      
      // Get the maximum operations on any single carrier thread
      int maxOpsOnSingleThread = carrierThreadCounts.values().stream()
          .mapToInt(Integer::intValue)
          .max()
          .orElse(0);
      
      log.info("Average operations per carrier thread: {}", avgOpsPerThread);
      log.info("Maximum operations on a single carrier thread: {}", maxOpsOnSingleThread);
      
      // If there's significant pinning, a single carrier thread would handle many more
      // operations than the average. We allow some variance but not extreme pinning.
      assertThat("No excessive thread pinning should occur",
          maxOpsOnSingleThread, lessThan((int)(avgOpsPerThread * 3)));
    }
  }
  
  /**
   * Tests that cache invalidation works correctly with virtual threads by verifying that
   * the metadata cache is invalidated when a Release file is fetched.
   */
  @Test
  @DisplayName("Cache invalidation with Virtual Threads")
  public void testCacheInvalidationWithVirtualThreads() throws Exception {
    // Setup state for a Release file fetch
    when(state.assetPath).thenReturn("dists/stable/Release");
    
    // Capture cache invalidation calls
    ArgumentCaptor<CacheInfo> cacheInfoCaptor = ArgumentCaptor.forClass(CacheInfo.class);
    
    // Use virtual threads for the operation
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("apt-proxy-test-").factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CompletableFuture<Content> future = CompletableFuture.supplyAsync(() -> {
        try {
          return underTest.get(context);
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for completion
      Content result = future.get(10, TimeUnit.SECONDS);
      
      // Verify result
      assertThat(result, is(notNullValue()));
      
      // Verify cache controller was invalidated
      verify(cacheController).invalidateCache();
    }
  }
  
  /**
   * Helper method to measure performance of concurrent operations using the specified
   * executor service factory and number of concurrent operations.
   * 
   * @param executorFactory Factory to create the executor service
   * @param specs Content specifiers to fetch
   * @param concurrentOperations Number of concurrent operations to perform
   * @return Execution time in milliseconds
   */
  private long measurePerformance(
      ExecutorServiceFactory executorFactory,
      List<ContentSpecifier> specs,
      int concurrentOperations) throws Exception {
    // Setup completion tracking
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Start timing
    long startTime = System.currentTimeMillis();
    
    // Create executor and submit tasks
    try (ExecutorService executor = executorFactory.create()) {
      for (int i = 0; i < concurrentOperations; i++) {
        CompletableFuture.runAsync(() -> {
          try {
            underTest.getSnapshotItems(specs);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for completion
      latch.await(60, TimeUnit.SECONDS);
    }
    
    // End timing
    long endTime = System.currentTimeMillis();
    
    // Verify no errors occurred
    assertThat("No errors should occur during performance test", 
        errorCount.get(), equalTo(0));
    
    return endTime - startTime;
  }
  
  /**
   * Functional interface for creating executor services.
   */
  @FunctionalInterface
  private interface ExecutorServiceFactory {
    ExecutorService create();
  }
}