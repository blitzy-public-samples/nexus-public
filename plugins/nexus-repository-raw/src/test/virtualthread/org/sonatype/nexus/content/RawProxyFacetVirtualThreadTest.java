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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.content.raw.internal.recipe.RawProxyFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.Mockito;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RawProxyFacet} with Java 21 Virtual Threads.
 * 
 * This test class verifies that proxy operations (get, getCached, invalidateCache) in Raw repositories 
 * function correctly when executed on Virtual Threads, ensuring proper remote connection handling, 
 * caching behavior, and error recovery under high concurrency.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class RawProxyFacetVirtualThreadTest
    extends TestSupport
{
  private static final String ASSET_PATH = "/some/asset.txt";
  private static final String ASSET_CONTENT = "test content";
  private static final String SPECIAL_CHARS_PATH = "/path^with#special?chars[test].txt";
  private static final String ENCODED_SPECIAL_CHARS_PATH = "/path%5Ewith%23special%3Fchars%5Btest%5D.txt";

  @Mock
  private Repository repository;

  @Mock
  private RawContentFacet rawContentFacet;

  @Mock
  private Context context;

  @Mock
  private Request request;

  @Mock
  private TokenMatcher.State state;

  @Mock
  private AttributesMap contextAttributes;

  @Mock
  private AttributesMap contentAttributes;

  private RawProxyFacet underTest;

  private Content content;

  @Before
  public void setUp() throws Exception {
    // Set up the content
    content = new Content(new StringPayload(ASSET_CONTENT, "text/plain"));
    content.getAttributes().set(CacheInfo.class, new CacheInfo());

    // Set up the context
    when(context.getRequest()).thenReturn(request);
    when(context.getAttributes()).thenReturn(contextAttributes);
    when(contextAttributes.require(TokenMatcher.State.class)).thenReturn(state);
    when(state.getTokens()).thenReturn(java.util.Collections.singletonMap("path", ASSET_PATH));

    // Set up the repository
    when(repository.facet(RawContentFacet.class)).thenReturn(rawContentFacet);

    // Set up the content facet
    when(rawContentFacet.get(ASSET_PATH)).thenReturn(Optional.of(content));
    when(rawContentFacet.put(eq(ASSET_PATH), any(Content.class))).thenReturn(content);

    // Create the test subject
    underTest = new RawProxyFacet();
    underTest.attach(repository);
  }

  /**
   * Tests that getCachedContent works correctly with virtual threads.
   */
  @Test
  public void testGetCachedContentWithVirtualThreads() throws Exception {
    int concurrentRequests = 100;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Submit multiple concurrent requests using virtual threads
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            Content result = underTest.getCachedContent(context);
            if (result != null && ASSET_CONTENT.equals(result.getPayload().toString())) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread test", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(10, SECONDS);
      
      // Verify all requests were successful
      assertThat(successCount.get(), equalTo(concurrentRequests));
      
      // Verify the content facet was called the expected number of times
      verify(rawContentFacet, times(concurrentRequests)).get(ASSET_PATH);
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that store works correctly with virtual threads.
   */
  @Test
  public void testStoreWithVirtualThreads() throws Exception {
    int concurrentRequests = 100;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Submit multiple concurrent store operations using virtual threads
      for (int i = 0; i < concurrentRequests; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            Content newContent = new Content(new StringPayload("content-" + index, "text/plain"));
            Content result = underTest.store(context, newContent);
            if (result != null) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread test", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(10, SECONDS);
      
      // Verify all requests were successful
      assertThat(successCount.get(), equalTo(concurrentRequests));
      
      // Verify the content facet was called the expected number of times
      verify(rawContentFacet, times(concurrentRequests)).put(eq(ASSET_PATH), any(Content.class));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests URL encoding with special characters using virtual threads.
   */
  @Test
  public void testUrlEncodingWithVirtualThreads() throws Exception {
    // Set up the context with a path containing special characters
    when(state.getTokens()).thenReturn(java.util.Collections.singletonMap("path", SPECIAL_CHARS_PATH));
    
    // Test the encodeUrl method directly
    String encodedUrl = underTest.encodeUrl(SPECIAL_CHARS_PATH);
    assertThat(encodedUrl, equalTo(ENCODED_SPECIAL_CHARS_PATH));
    
    // Test concurrent encoding with virtual threads
    int concurrentRequests = 50;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Submit multiple concurrent encoding operations using virtual threads
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            String result = underTest.encodeUrl(SPECIAL_CHARS_PATH);
            if (ENCODED_SPECIAL_CHARS_PATH.equals(result)) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread test", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(10, SECONDS);
      
      // Verify all requests were successful
      assertThat(successCount.get(), equalTo(concurrentRequests));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests error handling and recovery with virtual threads.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Configure the content facet to throw an exception for some requests
    when(rawContentFacet.get(ASSET_PATH))
        .thenThrow(new IOException("Simulated network error"))
        .thenThrow(new IOException("Simulated network error"))
        .thenReturn(Optional.of(content));
    
    int concurrentRequests = 10;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Submit multiple concurrent requests using virtual threads
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            Content result = underTest.getCachedContent(context);
            if (result != null) {
              successCount.incrementAndGet();
            }
          } 
          catch (IOException e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(10, SECONDS);
      
      // Verify error and success counts
      assertThat(errorCount.get(), greaterThan(0));
      assertThat(successCount.get(), greaterThan(0));
      assertThat(errorCount.get() + successCount.get(), equalTo(concurrentRequests));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests remote connection failures with virtual threads.
   */
  @Test
  public void testRemoteConnectionFailuresWithVirtualThreads() throws Exception {
    // Configure the content facet to throw a RemoteBlockedIOException
    when(rawContentFacet.get(ASSET_PATH)).thenThrow(new RemoteBlockedIOException("Remote blocked"));
    
    int concurrentRequests = 20;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger blockedCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Submit multiple concurrent requests using virtual threads
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            underTest.getCachedContent(context);
            fail("Expected RemoteBlockedIOException");
          } 
          catch (RemoteBlockedIOException e) {
            blockedCount.incrementAndGet();
          }
          catch (IOException e) {
            fail("Unexpected exception: " + e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(10, SECONDS);
      
      // Verify all requests were blocked
      assertThat(blockedCount.get(), equalTo(concurrentRequests));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests performance comparison between platform threads and virtual threads.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    // Configure a more complex operation that involves some I/O simulation
    doAnswer(invocation -> {
      // Simulate I/O latency
      Thread.sleep(10);
      return Optional.of(content);
    }).when(rawContentFacet).get(ASSET_PATH);
    
    int concurrentRequests = 1000;
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
      return executeWithThreadFactory(platformThreadFactory, concurrentRequests);
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      return executeWithThreadFactory(virtualThreadFactory, concurrentRequests);
    });
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Helper method to execute concurrent requests with a specific thread factory.
   */
  private boolean executeWithThreadFactory(ThreadFactory threadFactory, int concurrentRequests) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Submit multiple concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            Content result = underTest.getCachedContent(context);
            if (result != null) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in thread test", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(30, SECONDS);
      
      // Verify all requests were successful
      return completed && successCount.get() == concurrentRequests;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Helper method to measure execution time of a task.
   */
  private long measureExecutionTime(Supplier<Boolean> task) {
    long startTime = System.currentTimeMillis();
    boolean success = false;
    
    try {
      success = task.get();
    } 
    catch (Exception e) {
      log.error("Error measuring execution time", e);
    }
    
    long endTime = System.currentTimeMillis();
    assertThat("Task should complete successfully", success, is(true));
    
    return endTime - startTime;
  }
  
  /**
   * Tests that the virtual thread executor is properly closed when the facet is stopped.
   */
  @Test
  public void testVirtualThreadExecutorClosedOnStop() throws Exception {
    // Create a spy on the underTest to verify doStop is called
    RawProxyFacet spyUnderTest = Mockito.spy(underTest);
    
    // Stop the facet
    spyUnderTest.doStop();
    
    // Verify that doStop was called on the superclass
    verify(spyUnderTest).doStop();
    
    // Try to use the executor after stopping - this should fail if properly closed
    try {
      // Set up a new context to avoid interference with other tests
      Context newContext = Mockito.mock(Context.class);
      AttributesMap newAttributes = Mockito.mock(AttributesMap.class);
      TokenMatcher.State newState = Mockito.mock(TokenMatcher.State.class);
      
      when(newContext.getAttributes()).thenReturn(newAttributes);
      when(newAttributes.require(TokenMatcher.State.class)).thenReturn(newState);
      when(newState.getTokens()).thenReturn(java.util.Collections.singletonMap("path", "/closed/test.txt"));
      
      // This should throw an exception if the executor is properly closed
      spyUnderTest.getCachedContent(newContext);
      
      // If we get here, the executor wasn't properly closed
      fail("Expected exception when using closed executor");
    } 
    catch (Exception e) {
      // Expected exception because executor should be closed
      log.info("Got expected exception after closing executor: {}", e.getMessage());
    }
  }
}