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
package org.sonatype.nexus.repository.cache;

import java.util.ArrayList;
import java.util.List;
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
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteConnectionStatus;
import org.sonatype.nexus.repository.httpclient.RemoteConnectionStatusType;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NegativeCacheHandler} with Java 21 Virtual Threads.
 * 
 * This test class validates that the NegativeCacheHandler performs correctly when handling
 * many concurrent requests using virtual threads, including proper cache retrieval, storage,
 * and invalidation under high concurrency.
 */
public class VirtualThreadNegativeCacheHandlerTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private NegativeCacheFacet mockNegativeCacheFacet;

  @Mock
  private NegativeCacheKey mockNegativeCacheKey;

  @Mock
  private Context mockContext;

  @Mock
  private Request mockRequest;

  @Mock
  private Repository mockRepository;

  @Mock
  private HttpClientFacet mockHttpFacet;

  @Mock
  private RemoteConnectionStatus mockConnectionStatus;

  private NegativeCacheHandler underTest;
  
  // Thread-safe cache for testing concurrent operations
  private ConcurrentHashMap<NegativeCacheKey, Status> testCache;

  @Before
  public void setUp() {
    underTest = new NegativeCacheHandler();
    testCache = new ConcurrentHashMap<>();

    when(mockContext.getRequest()).thenReturn(mockRequest);
    when(mockContext.getRepository()).thenReturn(mockRepository);
    when(mockRepository.facet(HttpClientFacet.class)).thenReturn(mockHttpFacet);
    when(mockHttpFacet.getStatus()).thenReturn(mockConnectionStatus);
    when(mockConnectionStatus.getType()).thenReturn(RemoteConnectionStatusType.AVAILABLE);
    when(mockRequest.getAction()).thenReturn(HttpMethods.GET);
    when(mockRepository.facet(NegativeCacheFacet.class)).thenReturn(mockNegativeCacheFacet);
    when(mockNegativeCacheFacet.getCacheKey(mockContext)).thenReturn(mockNegativeCacheKey);
    
    // Set up the mock cache facet to use our thread-safe test cache
    when(mockNegativeCacheFacet.get(any(NegativeCacheKey.class))).thenAnswer(invocation -> {
      NegativeCacheKey key = invocation.getArgument(0);
      return testCache.get(key);
    });
    
    doAnswer(invocation -> {
      NegativeCacheKey key = invocation.getArgument(0);
      Status status = invocation.getArgument(1);
      testCache.put(key, status);
      return null;
    }).when(mockNegativeCacheFacet).put(any(NegativeCacheKey.class), any(Status.class));
    
    doAnswer(invocation -> {
      NegativeCacheKey key = invocation.getArgument(0);
      testCache.remove(key);
      return null;
    }).when(mockNegativeCacheFacet).invalidate(any(NegativeCacheKey.class));
  }

  /**
   * Tests that the NegativeCacheHandler correctly handles many concurrent requests using virtual threads.
   * This test verifies that under high concurrency:
   * 1. Cache hits return the cached response
   * 2. Cache misses proceed to the context
   * 3. 404 responses are cached
   * 4. Successful responses invalidate the cache
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up a mix of responses from the context
      Response notFoundResponse = HttpResponses.notFound("404");
      Response okResponse = HttpResponses.ok("200");
      
      // Use AtomicReference to safely capture any exceptions from worker threads
      AtomicReference<Exception> workerException = new AtomicReference<>();
      
      // Use CountDownLatch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      
      // Track statistics
      AtomicInteger cacheHits = new AtomicInteger(0);
      AtomicInteger cacheMisses = new AtomicInteger(0);
      AtomicInteger notFoundResponses = new AtomicInteger(0);
      AtomicInteger okResponses = new AtomicInteger(0);
      
      // Set up the context to alternate between 404 and 200 responses
      when(mockContext.proceed()).thenAnswer(new Answer<Response>() {
        private final AtomicInteger counter = new AtomicInteger(0);
        
        @Override
        public Response answer(InvocationOnMock invocation) {
          // Alternate between 404 and 200 responses
          if (counter.getAndIncrement() % 2 == 0) {
            notFoundResponses.incrementAndGet();
            return notFoundResponse;
          } else {
            okResponses.incrementAndGet();
            return okResponse;
          }
        }
      });
      
      // Submit concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Get the current cache state before the operation
            Status cachedStatus = mockNegativeCacheFacet.get(mockNegativeCacheKey);
            
            if (cachedStatus != null) {
              cacheHits.incrementAndGet();
            } else {
              cacheMisses.incrementAndGet();
            }
            
            // Execute the handler
            Response response = underTest.handle(mockContext);
            
            // Verify the response is not null
            assertThat(response, notNullValue());
          } catch (Exception e) {
            workerException.set(e);
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All operations should complete within the timeout", completed, is(true));
      
      // Check if any worker thread had an exception
      Exception exception = workerException.get();
      if (exception != null) {
        throw exception;
      }
      
      // Verify that we had both cache hits and misses
      assertThat("Should have some cache hits", cacheHits.get(), greaterThan(0));
      assertThat("Should have some cache misses", cacheMisses.get(), greaterThan(0));
      assertThat("Total operations should equal hits plus misses", 
          cacheHits.get() + cacheMisses.get(), equalTo(CONCURRENT_OPERATIONS));
      
      // Verify that we had both 404 and 200 responses from the context
      assertThat("Should have some 404 responses", notFoundResponses.get(), greaterThan(0));
      assertThat("Should have some 200 responses", okResponses.get(), greaterThan(0));
      
      // The number of context.proceed() calls should equal the number of cache misses
      assertThat("Context proceed calls should match cache misses", 
          notFoundResponses.get() + okResponses.get(), equalTo(cacheMisses.get()));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that the NegativeCacheHandler correctly handles cache invalidation under high concurrency
   * with virtual threads. This test verifies that when a successful response is received, the cache
   * entry is properly invalidated even with many concurrent operations.
   */
  @Test
  public void testConcurrentCacheInvalidationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Pre-populate the cache with a 404 status
      Status notFoundStatus = Status.failure(HttpStatus.NOT_FOUND, "404");
      testCache.put(mockNegativeCacheKey, notFoundStatus);
      
      // Set up the context to return a successful response
      Response okResponse = HttpResponses.ok("200");
      when(mockContext.proceed()).thenReturn(okResponse);
      
      // Use CountDownLatch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      
      // Use AtomicReference to safely capture any exceptions from worker threads
      AtomicReference<Exception> workerException = new AtomicReference<>();
      
      // Submit concurrent tasks using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            // Execute the handler
            underTest.handle(mockContext);
          } catch (Exception e) {
            workerException.set(e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All operations should complete within the timeout", completed, is(true));
      
      // Check if any worker thread had an exception
      Exception exception = workerException.get();
      if (exception != null) {
        throw exception;
      }
      
      // Verify that the cache entry was invalidated
      assertThat("Cache entry should be invalidated", testCache.get(mockNegativeCacheKey), nullValue());
      
      // Verify that the invalidate method was called at least once
      verify(mockNegativeCacheFacet, times(CONCURRENT_OPERATIONS)).invalidate(mockNegativeCacheKey);
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that the NegativeCacheHandler correctly handles cache population under high concurrency
   * with virtual threads. This test verifies that when a 404 response is received, the cache
   * entry is properly stored even with many concurrent operations.
   */
  @Test
  public void testConcurrentCachePopulationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Ensure the cache is empty to start
      testCache.clear();
      
      // Set up the context to return a 404 response
      Response notFoundResponse = HttpResponses.notFound("404");
      when(mockContext.proceed()).thenReturn(notFoundResponse);
      
      // Use CountDownLatch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      
      // Use AtomicReference to safely capture any exceptions from worker threads
      AtomicReference<Exception> workerException = new AtomicReference<>();
      
      // Submit concurrent tasks using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            // Execute the handler
            underTest.handle(mockContext);
          } catch (Exception e) {
            workerException.set(e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All operations should complete within the timeout", completed, is(true));
      
      // Check if any worker thread had an exception
      Exception exception = workerException.get();
      if (exception != null) {
        throw exception;
      }
      
      // Verify that the cache entry was populated
      Status cachedStatus = testCache.get(mockNegativeCacheKey);
      assertThat("Cache entry should be populated", cachedStatus, notNullValue());
      assertThat("Cache entry should have status code 404", cachedStatus.getCode(), is(HttpStatus.NOT_FOUND));
      
      // Verify that the put method was called at least once
      verify(mockNegativeCacheFacet, times(CONCURRENT_OPERATIONS)).put(any(NegativeCacheKey.class), any(Status.class));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Compares the performance of virtual threads vs platform threads when handling many concurrent
   * cache operations. This test helps validate the performance benefits of using virtual threads
   * for I/O-bound operations like cache access.
   */
  @Test
  public void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Create thread factories for both types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Measure performance with platform threads
    long platformThreadTime = measurePerformance(platformThreadFactory);
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    
    // Measure performance with virtual threads
    long virtualThreadTime = measurePerformance(virtualThreadFactory);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should generally be more efficient for this I/O-bound workload
    // but we don't make this a hard assertion as it depends on the test environment
    log.info("Performance ratio (platform/virtual): {}", (double) platformThreadTime / virtualThreadTime);
    
    // In most environments, virtual threads should be faster, but this is not guaranteed
    // so we log the results but don't assert on them
    if (virtualThreadTime < platformThreadTime) {
      log.info("Virtual threads were faster than platform threads as expected");
    } else {
      log.info("Platform threads were faster than virtual threads in this test run");
    }
  }
  
  /**
   * Helper method to measure the performance of cache operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (virtual or platform)
   * @return The execution time in milliseconds
   */
  private long measurePerformance(ThreadFactory threadFactory) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Clear the test cache
      testCache.clear();
      
      // Set up the context to alternate between 404 and 200 responses
      when(mockContext.proceed()).thenAnswer(new Answer<Response>() {
        private final AtomicInteger counter = new AtomicInteger(0);
        
        @Override
        public Response answer(InvocationOnMock invocation) {
          // Alternate between 404 and 200 responses
          if (counter.getAndIncrement() % 2 == 0) {
            return HttpResponses.notFound("404");
          } else {
            return HttpResponses.ok("200");
          }
        }
      });
      
      // Use CountDownLatch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      
      // Use AtomicReference to safely capture any exceptions from worker threads
      AtomicReference<Exception> workerException = new AtomicReference<>();
      
      // Start timing
      long startTime = System.currentTimeMillis();
      
      // Submit concurrent tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            // Execute the handler
            underTest.handle(mockContext);
          } catch (Exception e) {
            workerException.set(e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All operations should complete within the timeout", completed, is(true));
      
      // End timing
      long endTime = System.currentTimeMillis();
      
      // Check if any worker thread had an exception
      Exception exception = workerException.get();
      if (exception != null) {
        throw exception;
      }
      
      return endTime - startTime;
    } finally {
      executor.shutdown();
    }
  }
}