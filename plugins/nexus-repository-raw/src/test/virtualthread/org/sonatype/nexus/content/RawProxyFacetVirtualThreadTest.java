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
package org.sonatype.nexus.content;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.content.raw.internal.recipe.RawProxyFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RawProxyFacet} using Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class RawProxyFacetVirtualThreadTest
    extends TestSupport
{
  private static final String PATH_NAME = "path";
  private static final String TEST_PATH = "test/path.txt";
  private static final String TEST_CONTENT = "Test content for virtual thread testing";
  private static final int CONCURRENT_REQUESTS = 100;
  private static final int LARGE_CONCURRENT_REQUESTS = 1000;
  
  @Mock
  private Repository repository;
  
  @Mock
  private RawContentFacet rawContentFacet;
  
  @Mock
  private HttpClientFacet httpClientFacet;
  
  @Mock
  private ViewFacet viewFacet;
  
  @Mock
  private Context context;
  
  @Mock
  private TokenMatcher.State state;
  
  private RawProxyFacet underTest;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    // Create executors
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    // Set up the RawProxyFacet
    underTest = new RawProxyFacet();
    underTest.attach(repository);
    
    // Configure mocks
    when(repository.facet(RawContentFacet.class)).thenReturn(rawContentFacet);
    when(repository.facet(HttpClientFacet.class)).thenReturn(httpClientFacet);
    when(repository.facet(ViewFacet.class)).thenReturn(viewFacet);
    when(context.getAttributes()).thenReturn(Map.of(TokenMatcher.State.class.getName(), state));
    when(state.getTokens()).thenReturn(Map.of(PATH_NAME, TEST_PATH));
  }
  
  @After
  public void tearDown() throws Exception {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests concurrent content retrieval using Virtual Threads.
   * Verifies that multiple concurrent requests can be handled efficiently.
   */
  @Test
  public void testConcurrentGetWithVirtualThreads() throws Exception {
    // Set up mock for remote content
    setupMockRemoteContent();
    
    // Create a map to store results from concurrent requests
    Map<Integer, Content> results = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    // Submit concurrent get requests using virtual threads
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          Content content = underTest.get(context);
          results.put(index, content);
          return content;
        } 
        finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all requests to complete
    assertTrue("Timed out waiting for concurrent requests", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all futures completed successfully
    for (Future<?> future : futures) {
      assertNotNull(future.get());
    }
    
    // Verify results
    assertEquals(CONCURRENT_REQUESTS, results.size());
    for (Content content : results.values()) {
      assertNotNull(content);
      assertEquals(TEST_CONTENT, contentAsString(content));
    }
    
    // Verify the content was fetched remotely only once (subsequent requests use cache)
    verify(httpClientFacet, times(1)).get(any(URI.class));
  }
  
  /**
   * Tests URL encoding with concurrent requests using Virtual Threads.
   * Verifies that special characters in paths are properly encoded.
   */
  @Test
  public void testUrlEncodingWithVirtualThreads() throws Exception {
    // Create a list of paths with special characters
    List<String> specialPaths = List.of(
        "test/path with spaces.txt",
        "test/path#with#hash.txt",
        "test/path?with?question.txt",
        "test/path[with]brackets.txt",
        "test/path^with^caret.txt",
        "test/path\u202Fwith\u202Fnarrow-space.txt"
    );
    
    // Set up mock for getUrl to return the actual encoded URL
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      return new URI("http://example.com/" + underTest.encodeUrl(path));
    }).when(httpClientFacet).getUrl(anyString());
    
    // Set up mock for remote content
    setupMockRemoteContent();
    
    // Submit concurrent encoding requests using virtual threads
    List<Future<String>> futures = new ArrayList<>();
    for (String path : specialPaths) {
      futures.add(virtualThreadExecutor.submit(() -> {
        // Create a context with the special path
        Context specialContext = mock(Context.class);
        TokenMatcher.State specialState = mock(TokenMatcher.State.class);
        
        when(specialContext.getAttributes()).thenReturn(Map.of(TokenMatcher.State.class.getName(), specialState));
        when(specialState.getTokens()).thenReturn(Map.of(PATH_NAME, path));
        
        // Get the URL that would be used
        URI uri = underTest.getUrl(specialContext);
        return uri.toString();
      }));
    }
    
    // Verify all URLs are properly encoded
    for (Future<String> future : futures) {
      String url = future.get();
      assertNotNull(url);
      assertTrue("URL should be properly encoded: " + url, 
          !url.contains(" ") && !url.contains("#") && !url.contains("?") && 
          !url.contains("[") && !url.contains("]") && !url.contains("^"));
    }
  }
  
  /**
   * Tests caching behavior and invalidation with Virtual Threads.
   * Verifies that content is properly cached and can be invalidated.
   */
  @Test
  public void testCachingBehaviorWithVirtualThreads() throws Exception {
    // Set up mock for remote content
    setupMockRemoteContent();
    
    // First request should fetch from remote
    Content content1 = underTest.get(context);
    assertNotNull(content1);
    assertEquals(TEST_CONTENT, contentAsString(content1));
    
    // Verify remote was called once
    verify(httpClientFacet, times(1)).get(any(URI.class));
    
    // Set up mock for cached content
    Content cachedContent = mock(Content.class);
    CacheInfo cacheInfo = mock(CacheInfo.class);
    when(cachedContent.getAttributes()).thenReturn(Map.of(CacheInfo.class, cacheInfo));
    when(cacheInfo.isStale()).thenReturn(false);
    StringPayload cachedPayload = new StringPayload("Cached content", UTF_8);
    when(cachedContent.openInputStream()).thenReturn(new ByteArrayInputStream("Cached content".getBytes(UTF_8)));
    when(cachedContent.getPayload()).thenReturn(cachedPayload);
    
    // Set up mock for getCachedContent
    when(rawContentFacet.get(eq(TEST_PATH))).thenReturn(cachedContent);
    
    // Submit concurrent requests that should use cache
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger cacheHits = new AtomicInteger(0);
    
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          Content content = underTest.get(context);
          if ("Cached content".equals(contentAsString(content))) {
            cacheHits.incrementAndGet();
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    assertTrue("Timed out waiting for concurrent requests", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all requests used the cache
    assertEquals(CONCURRENT_REQUESTS, cacheHits.get());
    
    // Verify remote was still only called once (from the first request)
    verify(httpClientFacet, times(1)).get(any(URI.class));
    
    // Now invalidate the cache
    underTest.invalidateCache(context);
    
    // Verify the cache entry was removed
    verify(rawContentFacet).delete(eq(TEST_PATH));
    
    // Set up for a new remote fetch after invalidation
    when(rawContentFacet.get(eq(TEST_PATH))).thenReturn(null);
    setupMockRemoteContent();
    
    // Get content again - should fetch from remote
    Content content2 = underTest.get(context);
    assertNotNull(content2);
    assertEquals(TEST_CONTENT, contentAsString(content2));
    
    // Verify remote was called again
    verify(httpClientFacet, times(2)).get(any(URI.class));
  }
  
  /**
   * Tests error handling and recovery with remote connection failures using Virtual Threads.
   * Verifies that errors are properly propagated and the system can recover.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Set up mock to throw an exception for remote content
    doThrow(new RemoteBlockedIOException("Remote blocked"))
        .when(httpClientFacet).get(any(URI.class));
    
    // Create a latch to track concurrent error handling
    CountDownLatch errorLatch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit concurrent requests that should all fail
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          underTest.get(context);
          fail("Expected RemoteBlockedIOException");
        } 
        catch (RemoteBlockedIOException e) {
          // Expected exception
          errorCount.incrementAndGet();
        } 
        catch (Exception e) {
          fail("Unexpected exception: " + e);
        } 
        finally {
          errorLatch.countDown();
        }
      });
    }
    
    // Wait for all error handling to complete
    assertTrue("Timed out waiting for error handling", errorLatch.await(30, TimeUnit.SECONDS));
    
    // Verify all requests encountered the expected error
    assertEquals(CONCURRENT_REQUESTS, errorCount.get());
    
    // Now fix the remote and verify recovery
    setupMockRemoteContent();
    
    // Submit a new batch of requests that should succeed
    CountDownLatch recoveryLatch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          Content content = underTest.get(context);
          if (content != null && TEST_CONTENT.equals(contentAsString(content))) {
            successCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          fail("Unexpected exception during recovery: " + e);
        } 
        finally {
          recoveryLatch.countDown();
        }
      });
    }
    
    // Wait for all recovery requests to complete
    assertTrue("Timed out waiting for recovery", recoveryLatch.await(30, TimeUnit.SECONDS));
    
    // Verify all requests recovered successfully
    assertEquals(CONCURRENT_REQUESTS, successCount.get());
  }
  
  /**
   * Tests performance comparison between platform threads and virtual threads for remote operations.
   * Verifies that virtual threads provide better scalability under high concurrency.
   */
  @Test
  public void testPerformanceComparisonWithVirtualThreads() throws Exception {
    // Set up mock for remote content with artificial delay to simulate network latency
    setupMockRemoteContentWithDelay(50); // 50ms delay
    
    // Measure performance with platform threads
    long platformThreadTime = measurePerformance(platformThreadExecutor, LARGE_CONCURRENT_REQUESTS);
    
    // Reset for virtual thread test
    Mockito.reset(httpClientFacet);
    setupMockRemoteContentWithDelay(50); // 50ms delay
    
    // Measure performance with virtual threads
    long virtualThreadTime = measurePerformance(virtualThreadExecutor, LARGE_CONCURRENT_REQUESTS);
    
    // Log the results
    log.info("Platform thread execution time for {} requests: {} ms", LARGE_CONCURRENT_REQUESTS, platformThreadTime);
    log.info("Virtual thread execution time for {} requests: {} ms", LARGE_CONCURRENT_REQUESTS, virtualThreadTime);
    
    // Virtual threads should be more efficient with I/O-bound operations under high concurrency
    // The exact performance difference depends on the environment, but virtual threads should not be slower
    assertThat("Virtual threads should be at least as fast as platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.1)); // Allow 10% margin
    
    // With high concurrency, virtual threads should show better scaling
    if (LARGE_CONCURRENT_REQUESTS >= 1000) {
      assertThat("Virtual threads should show better scaling with high concurrency",
          virtualThreadTime, lessThan(platformThreadTime * 0.8)); // At least 20% faster
    }
  }
  
  /**
   * Measures the performance of concurrent content retrieval using the specified executor.
   * 
   * @param executor The executor service to use
   * @param concurrentRequests The number of concurrent requests to make
   * @return The execution time in milliseconds
   */
  private long measurePerformance(ExecutorService executor, int concurrentRequests) throws Exception {
    // Create a latch to track completion
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    
    // Record start time
    long startTime = System.currentTimeMillis();
    
    // Submit concurrent requests
    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(() -> {
        try {
          underTest.get(context);
        } 
        catch (Exception e) {
          log.error("Error during performance test", e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    latch.await();
    
    // Calculate and return execution time
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Sets up the mock for remote content retrieval.
   */
  private void setupMockRemoteContent() throws Exception {
    // Create a mock response with test content
    Response response = mock(Response.class);
    Content content = mock(Content.class);
    Payload payload = new BytesPayload(TEST_CONTENT.getBytes(UTF_8), "text/plain");
    
    when(content.getPayload()).thenReturn(payload);
    when(content.openInputStream()).thenReturn(new ByteArrayInputStream(TEST_CONTENT.getBytes(UTF_8)));
    when(content.getAttributes()).thenReturn(Map.of());
    when(response.getPayload()).thenReturn(content);
    when(response.getStatus()).thenReturn(org.sonatype.nexus.repository.http.HttpStatus.OK);
    
    // Set up the HTTP client to return the mock response
    when(httpClientFacet.get(any(URI.class))).thenReturn(response);
    
    // Set up the URL generation
    when(httpClientFacet.getUrl(anyString())).thenReturn(new URI("http://example.com/" + TEST_PATH));
    
    // Set up the content facet to return null (not cached) initially
    when(rawContentFacet.get(anyString())).thenReturn(null);
    
    // Set up the content facet to store content
    doReturn(content).when(rawContentFacet).put(anyString(), any(Content.class));
  }
  
  /**
   * Sets up the mock for remote content retrieval with an artificial delay.
   * 
   * @param delayMs The delay in milliseconds
   */
  private void setupMockRemoteContentWithDelay(long delayMs) throws Exception {
    // Create a mock response with test content
    Response response = mock(Response.class);
    Content content = mock(Content.class);
    Payload payload = new BytesPayload(TEST_CONTENT.getBytes(UTF_8), "text/plain");
    
    when(content.getPayload()).thenReturn(payload);
    when(content.openInputStream()).thenReturn(new ByteArrayInputStream(TEST_CONTENT.getBytes(UTF_8)));
    when(content.getAttributes()).thenReturn(Map.of());
    when(response.getPayload()).thenReturn(content);
    when(response.getStatus()).thenReturn(org.sonatype.nexus.repository.http.HttpStatus.OK);
    
    // Set up the HTTP client to return the mock response with delay
    doAnswer(invocation -> {
      // Simulate network latency
      Thread.sleep(delayMs);
      return response;
    }).when(httpClientFacet).get(any(URI.class));
    
    // Set up the URL generation
    when(httpClientFacet.getUrl(anyString())).thenReturn(new URI("http://example.com/" + TEST_PATH));
    
    // Set up the content facet to return null (not cached)
    when(rawContentFacet.get(anyString())).thenReturn(null);
    
    // Set up the content facet to store content
    doReturn(content).when(rawContentFacet).put(anyString(), any(Content.class));
  }
  
  /**
   * Converts Content to a String for verification.
   */
  private String contentAsString(Content content) throws IOException {
    try (InputStream is = content.openInputStream()) {
      return new String(is.readAllBytes(), UTF_8);
    }
  }
}