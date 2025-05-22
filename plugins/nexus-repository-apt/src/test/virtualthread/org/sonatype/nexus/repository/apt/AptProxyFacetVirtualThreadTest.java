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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.payloads.HttpEntityPayload;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.apt.internal.ReleaseName.RELEASE;

/**
 * Tests for {@link AptProxyFacet} with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class AptProxyFacetVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final String TEST_PATH = "dists/bionic/main/binary-amd64/Packages.gz";
  private static final String RELEASE_PATH = "dists/bionic/" + RELEASE;
  private static final int CONCURRENT_REQUESTS = 100;
  private static final int SIMULATED_REMOTE_DELAY_MS = 50;
  
  @Mock
  private Repository repository;
  
  @Mock
  private AptContentFacet aptContentFacet;
  
  @Mock
  private HttpClientFacet httpClientFacet;
  
  @Mock
  private ProxyFacet proxyFacet;
  
  @Mock
  private HttpClient httpClient;
  
  @Mock
  private CacheControllerHolder cacheControllerHolder;
  
  @Mock
  private CacheController contentCacheController;
  
  @Mock
  private CacheController metadataCacheController;
  
  @Mock
  private Context context;
  
  @Mock
  private AttributesMap contextAttributes;
  
  @Mock
  private AptSnapshotHandler.State state;
  
  private AptProxyFacet underTest;
  
  @Before
  public void setUp() throws Exception {
    // Skip test if virtual threads are not supported
    assumeVirtualThreadSupported();
    
    // Setup mocks
    when(repository.facet(AptContentFacet.class)).thenReturn(aptContentFacet);
    when(repository.facet(HttpClientFacet.class)).thenReturn(httpClientFacet);
    when(repository.facet(ProxyFacet.class)).thenReturn(proxyFacet);
    when(httpClientFacet.getHttpClient()).thenReturn(httpClient);
    
    when(cacheControllerHolder.getContentCacheController()).thenReturn(contentCacheController);
    when(cacheControllerHolder.getMetadataCacheController()).thenReturn(metadataCacheController);
    
    when(context.getAttributes()).thenReturn(contextAttributes);
    when(contextAttributes.require(AptSnapshotHandler.State.class)).thenReturn(state);
    
    // Create the test subject
    underTest = spy(new AptProxyFacet());
    underTest.attach(repository);
    underTest.cacheControllerHolder = cacheControllerHolder;
  }
  
  /**
   * Tests that the AptProxyFacet can handle multiple concurrent requests using Virtual Threads.
   * This verifies that the implementation works correctly under high concurrency scenarios.
   */
  @Test
  public void testConcurrentFetchWithVirtualThreads() throws Exception {
    // Setup mocks for remote fetch
    URI remoteUri = new URI("http://example.com/");
    when(proxyFacet.getRemoteUrl()).thenReturn(remoteUri);
    when(state.assetPath).thenReturn(TEST_PATH);
    
    // Mock HTTP response
    HttpResponse response = mock(HttpResponse.class);
    StatusLine statusLine = mock(StatusLine.class);
    HttpEntity entity = mock(HttpEntity.class);
    when(response.getStatusLine()).thenReturn(statusLine);
    when(statusLine.getStatusCode()).thenReturn(200);
    when(response.getEntity()).thenReturn(entity);
    
    // Simulate network delay to test virtual thread behavior
    doAnswer(invocation -> {
      // Simulate network latency
      Thread.sleep(SIMULATED_REMOTE_DELAY_MS);
      return response;
    }).when(httpClient).execute(any(HttpGet.class));
    
    // Mock content storage
    Content content = mock(Content.class);
    AttributesMap contentAttributes = mock(AttributesMap.class);
    when(content.getAttributes()).thenReturn(contentAttributes);
    doReturn(content).when(aptContentFacet).put(eq(TEST_PATH), any(Content.class));
    
    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Execute concurrent requests using virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Get snapshot items
            List<ContentSpecifier> specs = List.of(new ContentSpecifier(TEST_PATH));
            List<SnapshotItem> items = underTest.getSnapshotItems(specs);
            
            // Verify result
            if (items != null && !items.isEmpty()) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread execution", e);
          }
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all requests to complete
      assertTrue("Timed out waiting for concurrent requests to complete",
          latch.await(30, TimeUnit.SECONDS));
      
      // Verify all requests were successful
      assertThat(successCount.get(), is(CONCURRENT_REQUESTS));
      
      // Verify HTTP client was called the expected number of times
      verify(httpClient, times(CONCURRENT_REQUESTS)).execute(any(HttpGet.class));
    }
  }
  
  /**
   * Tests that the AptProxyFacet correctly invalidates the cache when fetching a release file.
   * This verifies that cache invalidation works correctly with virtual threads.
   */
  @Test
  public void testCacheInvalidationWithVirtualThreads() throws Exception {
    // Setup mocks for remote fetch
    when(state.assetPath).thenReturn(RELEASE_PATH);
    
    // Mock content retrieval and storage
    Content content = mock(Content.class);
    AttributesMap contentAttributes = mock(AttributesMap.class);
    when(content.getAttributes()).thenReturn(contentAttributes);
    
    // Setup content facet to return content
    when(aptContentFacet.get(RELEASE_PATH)).thenReturn(Optional.empty());
    when(aptContentFacet.put(eq(RELEASE_PATH), any(Content.class))).thenReturn(aptContentFacet);
    when(aptContentFacet.markAsCached(any(Content.class))).thenReturn(aptContentFacet);
    when(aptContentFacet.download()).thenReturn(content);
    
    // Execute the test on a virtual thread
    runVirtual(() -> {
      // Store content
      Content result = underTest.store(context, content);
      
      // Verify result
      assertThat(result, is(notNullValue()));
      
      // Verify cache was invalidated
      verify(metadataCacheController).invalidateCache();
    });
  }
  
  /**
   * Tests that the AptProxyFacet does not experience thread pinning during proxy operations.
   * Thread pinning would negate the benefits of virtual threads by forcing them to occupy
   * a carrier thread for their entire execution.
   */
  @Test
  public void testNoThreadPinningDuringProxyOperations() throws Exception {
    // Setup mocks for remote fetch
    URI remoteUri = new URI("http://example.com/");
    when(proxyFacet.getRemoteUrl()).thenReturn(remoteUri);
    when(state.assetPath).thenReturn(TEST_PATH);
    
    // Mock HTTP response
    HttpResponse response = mock(HttpResponse.class);
    StatusLine statusLine = mock(StatusLine.class);
    HttpEntity entity = mock(HttpEntity.class);
    when(response.getStatusLine()).thenReturn(statusLine);
    when(statusLine.getStatusCode()).thenReturn(200);
    when(response.getEntity()).thenReturn(entity);
    
    // Simulate network delay without blocking the thread
    doAnswer(invocation -> {
      // Use a non-blocking delay that won't pin the thread
      Thread.sleep(SIMULATED_REMOTE_DELAY_MS);
      return response;
    }).when(httpClient).execute(any(HttpGet.class));
    
    // Mock content storage
    Content content = mock(Content.class);
    AttributesMap contentAttributes = mock(AttributesMap.class);
    when(content.getAttributes()).thenReturn(contentAttributes);
    doReturn(content).when(aptContentFacet).put(eq(TEST_PATH), any(Content.class));
    
    // Check for thread pinning
    boolean pinningDetected = detectThreadPinning(() -> {
      try {
        // Get snapshot items
        List<ContentSpecifier> specs = List.of(new ContentSpecifier(TEST_PATH));
        underTest.getSnapshotItems(specs);
      }
      catch (Exception e) {
        log.error("Error during thread pinning test", e);
      }
    });
    
    // Verify no thread pinning occurred
    assertFalse("Thread pinning detected during proxy operations", pinningDetected);
  }
  
  /**
   * Compares the performance of virtual threads vs platform threads for proxy operations.
   * This test validates that virtual threads provide better throughput for I/O-bound operations.
   */
  @Test
  public void testVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Setup mocks for remote fetch
    URI remoteUri = new URI("http://example.com/");
    when(proxyFacet.getRemoteUrl()).thenReturn(remoteUri);
    when(state.assetPath).thenReturn(TEST_PATH);
    
    // Mock HTTP response with delay to simulate network latency
    HttpResponse response = mock(HttpResponse.class);
    StatusLine statusLine = mock(StatusLine.class);
    HttpEntity entity = mock(HttpEntity.class);
    when(response.getStatusLine()).thenReturn(statusLine);
    when(statusLine.getStatusCode()).thenReturn(200);
    when(response.getEntity()).thenReturn(entity);
    
    doAnswer(invocation -> {
      // Simulate network latency
      Thread.sleep(SIMULATED_REMOTE_DELAY_MS);
      return response;
    }).when(httpClient).execute(any(HttpGet.class));
    
    // Mock content storage
    Content content = mock(Content.class);
    AttributesMap contentAttributes = mock(AttributesMap.class);
    when(content.getAttributes()).thenReturn(contentAttributes);
    doReturn(content).when(aptContentFacet).put(eq(TEST_PATH), any(Content.class));
    
    // Prepare test data
    List<ContentSpecifier> specs = List.of(new ContentSpecifier(TEST_PATH));
    int testIterations = 50;
    
    // Test with platform threads
    long platformThreadStart = System.currentTimeMillis();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(10)) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < testIterations; i++) {
        futures.add(platformExecutor.submit(() -> {
          try {
            underTest.getSnapshotItems(specs);
          }
          catch (Exception e) {
            log.error("Error in platform thread execution", e);
          }
        }));
      }
      
      // Wait for all platform thread tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }
    long platformThreadTime = System.currentTimeMillis() - platformThreadStart;
    
    // Reset mocks for virtual thread test
    Mockito.reset(httpClient);
    doAnswer(invocation -> {
      // Simulate network latency
      Thread.sleep(SIMULATED_REMOTE_DELAY_MS);
      return response;
    }).when(httpClient).execute(any(HttpGet.class));
    
    // Test with virtual threads
    long virtualThreadStart = System.currentTimeMillis();
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < testIterations; i++) {
        futures.add(virtualExecutor.submit(() -> {
          try {
            underTest.getSnapshotItems(specs);
          }
          catch (Exception e) {
            log.error("Error in virtual thread execution", e);
          }
        }));
      }
      
      // Wait for all virtual thread tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }
    long virtualThreadTime = System.currentTimeMillis() - virtualThreadStart;
    
    // Log performance results
    log.info("Performance comparison for {} iterations:", testIterations);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
    log.info("Improvement: {}%", (platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime);
    
    // Virtual threads should be faster for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests that the AptProxyFacet correctly handles conditional GET requests with virtual threads.
   * This verifies that the implementation correctly processes HTTP 304 responses.
   */
  @Test
  public void testConditionalGetWithVirtualThreads() throws Exception {
    // Setup mocks for remote fetch
    URI remoteUri = new URI("http://example.com/");
    when(proxyFacet.getRemoteUrl()).thenReturn(remoteUri);
    when(state.assetPath).thenReturn(TEST_PATH);
    
    // Mock existing content
    Content existingContent = mock(Content.class);
    AttributesMap existingAttributes = mock(AttributesMap.class);
    when(existingContent.getAttributes()).thenReturn(existingAttributes);
    when(aptContentFacet.get(TEST_PATH)).thenReturn(Optional.of(existingContent));
    
    // Mock HTTP 304 response
    HttpResponse response = mock(HttpResponse.class);
    StatusLine statusLine = mock(StatusLine.class);
    when(response.getStatusLine()).thenReturn(statusLine);
    when(statusLine.getStatusCode()).thenReturn(304); // Not Modified
    
    // Mock cache info
    CacheInfo cacheInfo = mock(CacheInfo.class);
    when(metadataCacheController.current()).thenReturn(cacheInfo);
    
    // Setup asset for cache verification
    Asset asset = mock(Asset.class);
    when(existingAttributes.get(Asset.class)).thenReturn(asset);
    
    // Simulate network delay
    doAnswer(invocation -> {
      Thread.sleep(SIMULATED_REMOTE_DELAY_MS);
      return response;
    }).when(httpClient).execute(any(HttpGet.class));
    
    // Execute the test on a virtual thread
    runVirtual(() -> {
      // Get snapshot items
      List<ContentSpecifier> specs = List.of(new ContentSpecifier(TEST_PATH));
      List<SnapshotItem> items = underTest.getSnapshotItems(specs);
      
      // Verify result
      assertThat(items, is(notNullValue()));
      assertThat(items.size(), is(1));
      assertThat(items.get(0).content, is(existingContent));
      
      // Verify asset was marked as cached
      verify(aptContentFacet.assets()).with(asset);
    });
  }
  
  /**
   * Tests that the AptProxyFacet correctly handles concurrent cache invalidation with virtual threads.
   * This verifies that the implementation correctly handles concurrent modifications to the cache.
   */
  @Test
  public void testConcurrentCacheInvalidation() throws Exception {
    // Setup mocks for remote fetch
    when(state.assetPath).thenReturn(RELEASE_PATH);
    
    // Mock content retrieval and storage
    Content content = mock(Content.class);
    AttributesMap contentAttributes = mock(AttributesMap.class);
    when(content.getAttributes()).thenReturn(contentAttributes);
    
    // Setup content facet to return content
    when(aptContentFacet.get(RELEASE_PATH)).thenReturn(Optional.empty());
    when(aptContentFacet.put(eq(RELEASE_PATH), any(Content.class))).thenReturn(aptContentFacet);
    when(aptContentFacet.markAsCached(any(Content.class))).thenReturn(aptContentFacet);
    when(aptContentFacet.download()).thenReturn(content);
    
    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Execute concurrent requests using virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Store content
            Content result = underTest.store(context, content);
            
            // Verify result
            if (result != null) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread execution", e);
          }
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all requests to complete
      assertTrue("Timed out waiting for concurrent requests to complete",
          latch.await(30, TimeUnit.SECONDS));
      
      // Verify all requests were successful
      assertThat(successCount.get(), is(CONCURRENT_REQUESTS));
      
      // Verify cache was invalidated the expected number of times
      verify(metadataCacheController, times(CONCURRENT_REQUESTS)).invalidateCache();
    }
  }
}