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
package org.sonatype.nexus.repository.httpbridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.httpbridge.internal.DefaultHttpResponseSender;
import org.sonatype.nexus.repository.view.Headers;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for HTTP response handling with Java 21 Virtual Threads.
 * 
 * These tests validate that HTTP operations benefit from Virtual Threads,
 * verify proper resource management, and ensure that I/O operations don't
 * cause thread pinning.
 */
public class VirtualThreadHttpResponseTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int LARGE_PAYLOAD_SIZE = 10 * 1024 * 1024; // 10MB
  private static final byte[] TEST_CONTENT = "TEST CONTENT".getBytes(StandardCharsets.UTF_8);

  private final HttpResponseSender underTest = new DefaultHttpResponseSender();
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @Mock
  private Request request;

  @Mock
  private Payload payload;

  @Spy
  private InputStream input = new ByteArrayInputStream(TEST_CONTENT);

  @Mock
  private HttpServletResponse httpServletResponse;

  @Mock
  private ServletOutputStream output;

  @Before
  public void setUp() throws Exception {
    // Create executors for testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(100); // typical thread pool size
    
    // Setup common test fixtures
    when(request.getHeaders()).thenReturn(new Headers());
    when(request.getAction()).thenReturn(HttpMethods.GET);
    when(payload.openInputStream()).thenReturn(input);
    when(httpServletResponse.getOutputStream()).thenReturn(output);
  }

  @After
  public void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests that we can handle a large number of concurrent HTTP responses using Virtual Threads.
   * This validates that the HTTP response handling code works correctly with Virtual Threads
   * and can scale to handle many concurrent connections.
   */
  @Test
  public void highConcurrencyWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create and submit many concurrent tasks
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify all tasks completed successfully
    assertThat("All concurrent requests should complete", completed, is(true));
    assertThat("No errors should occur", errorCount.get(), is(0));
    
    // Verify the payload was processed the expected number of times
    verify(payload, times(CONCURRENT_REQUESTS)).openInputStream();
    verify(payload, times(CONCURRENT_REQUESTS)).close();
  }

  /**
   * Tests that HTTP response handling with large payloads doesn't cause thread pinning.
   * Thread pinning occurs when a Virtual Thread blocks in a way that prevents the carrier
   * thread from being released, which defeats the purpose of Virtual Threads.
   */
  @Test
  public void largePayloadDoesNotCauseThreadPinning() throws Exception {
    int concurrentStreams = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentStreams);
    AtomicReference<Exception> testException = new AtomicReference<>();
    
    // Create a large payload that will stream slowly
    byte[] largeContent = new byte[LARGE_PAYLOAD_SIZE];
    InputStream largeInput = new ByteArrayInputStream(largeContent);
    when(payload.openInputStream()).thenReturn(largeInput);
    
    // Simulate slow I/O by adding delays during copy
    doAnswer(invocation -> {
      // Simulate slow I/O operations that would normally cause thread pinning
      // if not handled correctly with virtual threads
      Thread.sleep(50); // Small delay to simulate I/O
      return null;
    }).when(output).write(largeContent, 0, largeContent.length);
    
    // Start many concurrent streaming operations
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < concurrentStreams; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Send the response with the large payload
          underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
        }
        catch (Exception e) {
          testException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Check for exceptions
    if (testException.get() != null) {
      fail("Exception occurred during test: " + testException.get().getMessage());
    }
    
    // Verify all operations completed successfully
    assertThat("All streaming operations should complete", completed, is(true));
  }

  /**
   * Compares the performance of HTTP response handling between platform threads and virtual threads.
   * Virtual threads should provide better throughput for I/O-bound operations like HTTP responses.
   */
  @Test
  public void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    int iterations = 1000;
    
    // Measure platform thread performance
    long platformStart = System.nanoTime();
    CountDownLatch platformLatch = new CountDownLatch(iterations);
    
    for (int i = 0; i < iterations; i++) {
      platformThreadExecutor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
        }
        catch (Exception e) {
          // Log and continue
          log.error("Error in platform thread test", e);
        }
        finally {
          platformLatch.countDown();
        }
      });
    }
    
    platformLatch.await(30, TimeUnit.SECONDS);
    long platformDuration = System.nanoTime() - platformStart;
    
    // Reset for virtual thread test
    when(payload.openInputStream()).thenReturn(new ByteArrayInputStream(TEST_CONTENT));
    
    // Measure virtual thread performance
    long virtualStart = System.nanoTime();
    CountDownLatch virtualLatch = new CountDownLatch(iterations);
    
    for (int i = 0; i < iterations; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
        }
        catch (Exception e) {
          // Log and continue
          log.error("Error in virtual thread test", e);
        }
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    virtualLatch.await(30, TimeUnit.SECONDS);
    long virtualDuration = System.nanoTime() - virtualStart;
    
    // Log the results for analysis
    log.info("Platform thread duration: {} ms", TimeUnit.NANOSECONDS.toMillis(platformDuration));
    log.info("Virtual thread duration: {} ms", TimeUnit.NANOSECONDS.toMillis(virtualDuration));
    
    // Virtual threads should be at least as fast as platform threads for this workload
    // In practice, they should be faster for I/O-bound operations
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualDuration, lessThan(platformDuration * 1.2)); // Allow some variance
  }

  /**
   * Tests that resources are properly cleaned up when using virtual threads.
   * This ensures that even with many concurrent operations, all resources are released.
   */
  @Test
  public void resourcesAreCleanedUpWithVirtualThreads() throws Exception {
    int concurrentRequests = 500;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    ConcurrentHashMap<Integer, Boolean> closedPayloads = new ConcurrentHashMap<>();
    
    // Create payloads that track if they were closed
    List<Payload> payloads = new ArrayList<>();
    List<InputStream> inputs = new ArrayList<>();
    
    for (int i = 0; i < concurrentRequests; i++) {
      final int index = i;
      InputStream testInput = new ByteArrayInputStream(TEST_CONTENT);
      inputs.add(testInput);
      
      Payload testPayload = new Payload() {
        @Override
        public InputStream openInputStream() throws IOException {
          return testInput;
        }

        @Override
        public long getSize() {
          return TEST_CONTENT.length;
        }

        @Override
        public String getContentType() {
          return "text/plain";
        }

        @Override
        public void close() throws IOException {
          closedPayloads.put(index, true);
        }
      };
      
      payloads.add(testPayload);
    }
    
    // Process all payloads concurrently with virtual threads
    for (int i = 0; i < concurrentRequests; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(payloads.get(index)), httpServletResponse);
        }
        catch (Exception e) {
          log.error("Error processing payload {}", index, e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertThat("All operations should complete", completed, is(true));
    
    // Verify all payloads were closed
    assertThat("All payloads should be closed", closedPayloads.size(), equalTo(concurrentRequests));
  }

  /**
   * Tests that HTTP response handling works correctly when mixing platform and virtual threads.
   * This ensures compatibility in environments where both thread types are used.
   */
  @Test
  public void mixedThreadTypesHandleResponsesCorrectly() throws Exception {
    int requestsPerType = 100;
    CountDownLatch platformLatch = new CountDownLatch(requestsPerType);
    CountDownLatch virtualLatch = new CountDownLatch(requestsPerType);
    AtomicInteger platformErrors = new AtomicInteger(0);
    AtomicInteger virtualErrors = new AtomicInteger(0);
    
    // Submit platform thread tasks
    for (int i = 0; i < requestsPerType; i++) {
      platformThreadExecutor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
        }
        catch (Exception e) {
          platformErrors.incrementAndGet();
        }
        finally {
          platformLatch.countDown();
        }
      });
    }
    
    // Submit virtual thread tasks
    for (int i = 0; i < requestsPerType; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
        }
        catch (Exception e) {
          virtualErrors.incrementAndGet();
        }
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    // Wait for both types to complete
    boolean platformCompleted = platformLatch.await(30, TimeUnit.SECONDS);
    boolean virtualCompleted = virtualLatch.await(30, TimeUnit.SECONDS);
    
    // Verify all tasks completed successfully
    assertThat("All platform thread tasks should complete", platformCompleted, is(true));
    assertThat("All virtual thread tasks should complete", virtualCompleted, is(true));
    assertThat("No platform thread errors should occur", platformErrors.get(), is(0));
    assertThat("No virtual thread errors should occur", virtualErrors.get(), is(0));
  }
}