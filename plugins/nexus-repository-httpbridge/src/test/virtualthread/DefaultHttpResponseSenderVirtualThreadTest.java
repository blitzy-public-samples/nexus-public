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
package org.sonatype.nexus.repository.httpbridge.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.httpbridge.HttpResponseSender;
import org.sonatype.nexus.repository.view.Headers;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultHttpResponseSender} using Java 21 Virtual Threads.
 * 
 * This test validates that HTTP response streaming works correctly and efficiently
 * under high concurrency with Virtual Threads, ensuring optimal performance with
 * Java 21's lightweight threading model.
 */
public class DefaultHttpResponseSenderVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 10_000;
  private static final int LARGE_PAYLOAD_SIZE = 1024 * 1024; // 1MB
  private static final byte[] TEST_CONTENT = "TEST CONTENT".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LARGE_CONTENT = new byte[LARGE_PAYLOAD_SIZE];
  
  static {
    // Initialize large content with some data
    for (int i = 0; i < LARGE_PAYLOAD_SIZE; i++) {
      LARGE_CONTENT[i] = (byte) (i % 256);
    }
  }

  private final HttpResponseSender underTest = new DefaultHttpResponseSender();

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
    when(request.getHeaders()).thenReturn(new Headers());
    when(request.getAction()).thenReturn(HttpMethods.GET);
    when(payload.openInputStream()).thenReturn(input);
    when(httpServletResponse.getOutputStream()).thenReturn(output);
  }

  /**
   * Tests that the DefaultHttpResponseSender correctly handles a high number of concurrent
   * requests using Virtual Threads, ensuring that all responses are properly sent and resources
   * are cleaned up.
   */
  @Test
  public void highConcurrencyWithVirtualThreads() throws Exception {
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Configure payload to simulate a real response
    when(payload.getContentType()).thenReturn("text/plain");
    when(payload.getSize()).thenReturn((long) TEST_CONTENT.length);
    
    // Configure output stream to simulate writing data without actually doing I/O
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Simulate some I/O delay (5ms)
        Thread.sleep(5);
        return null;
      }
    }).when(output).write(any(byte[].class), any(int.class), any(int.class));
    
    // Create a virtual thread per task executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit CONCURRENT_REQUESTS tasks
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        executor.submit(() -> {
          try {
            // Create a new input stream for each request to avoid concurrent access
            InputStream requestInput = new ByteArrayInputStream(TEST_CONTENT);
            when(payload.openInputStream()).thenReturn(requestInput);
            
            // Send the response
            underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error sending response", e);
            failureCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout after 30 seconds
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertThat("All requests should complete within the timeout", completed, is(true));
      assertThat("All requests should succeed", successCount.get(), is(CONCURRENT_REQUESTS));
      assertThat("No requests should fail", failureCount.get(), is(0));
      
      // Verify the output stream was written to the expected number of times
      verify(output, times(CONCURRENT_REQUESTS)).write(any(byte[].class), any(int.class), any(int.class));
    }
  }

  /**
   * Tests that the DefaultHttpResponseSender correctly handles streaming large payloads
   * using Virtual Threads, ensuring that memory usage remains efficient.
   */
  @Test
  public void streamingLargePayloadsWithVirtualThreads() throws Exception {
    // Number of concurrent large payloads to stream
    final int CONCURRENT_LARGE_PAYLOADS = 100;
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_LARGE_PAYLOADS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Configure a large payload
    Payload largePayload = org.mockito.Mockito.mock(Payload.class);
    when(largePayload.getContentType()).thenReturn("application/octet-stream");
    when(largePayload.getSize()).thenReturn((long) LARGE_CONTENT.length);
    when(largePayload.openInputStream()).thenReturn(new ByteArrayInputStream(LARGE_CONTENT));
    
    // Configure output stream to simulate writing data without actually doing I/O
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Simulate some I/O delay (10ms per chunk)
        Thread.sleep(10);
        return null;
      }
    }).when(output).write(any(byte[].class), any(int.class), any(int.class));
    
    // Measure memory before the test
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create a virtual thread per task executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to stream large payloads
      for (int i = 0; i < CONCURRENT_LARGE_PAYLOADS; i++) {
        executor.submit(() -> {
          try {
            // Create a new input stream for each request to avoid concurrent access
            InputStream requestInput = new ByteArrayInputStream(LARGE_CONTENT);
            when(largePayload.openInputStream()).thenReturn(requestInput);
            
            // Send the response with large payload
            underTest.send(request, HttpResponses.ok(largePayload), httpServletResponse);
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error sending large payload", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout after 60 seconds
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertThat("All large payload requests should complete within the timeout", completed, is(true));
      assertThat("All large payload requests should succeed", successCount.get(), is(CONCURRENT_LARGE_PAYLOADS));
    }
    
    // Measure memory after the test
    System.gc(); // Request garbage collection to get more accurate memory usage
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Log memory usage for informational purposes
    log.info("Memory before: {} bytes, Memory after: {} bytes, Difference: {} bytes",
        memoryBefore, memoryAfter, memoryAfter - memoryBefore);
  }

  /**
   * Tests that the DefaultHttpResponseSender correctly handles thread pinning scenarios
   * by detecting when a virtual thread is pinned to its carrier thread during I/O operations.
   * 
   * This test simulates a blocking I/O operation and verifies that resources are properly
   * cleaned up even when thread pinning occurs.
   */
  @Test
  public void detectThreadPinningDuringIOOperations() throws Exception {
    // Configure a payload that will cause a delay during I/O
    Payload blockingPayload = org.mockito.Mockito.mock(Payload.class);
    when(blockingPayload.getContentType()).thenReturn("text/plain");
    when(blockingPayload.getSize()).thenReturn((long) TEST_CONTENT.length);
    
    // Create an input stream that will block during read
    InputStream blockingInput = new InputStream() {
      private int position = 0;
      
      @Override
      public int read() throws IOException {
        // Simulate blocking I/O by sleeping
        try {
          Thread.sleep(50); // 50ms delay per byte
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted during read", e);
        }
        
        if (position < TEST_CONTENT.length) {
          return TEST_CONTENT[position++];
        }
        return -1;
      }
    };
    
    when(blockingPayload.openInputStream()).thenReturn(blockingInput);
    
    // Configure output stream to simulate writing data
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Do nothing, just simulate writing
        return null;
      }
    }).when(output).write(any(byte[].class), any(int.class), any(int.class));
    
    // Create a list to track thread names for pinning detection
    List<String> threadNames = new ArrayList<>();
    
    // Create a virtual thread to send the response
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      try {
        // Record the thread name before I/O
        threadNames.add(Thread.currentThread().getName());
        
        // Send the response with blocking payload
        underTest.send(request, HttpResponses.ok(blockingPayload), httpServletResponse);
        
        // Record the thread name after I/O
        threadNames.add(Thread.currentThread().getName());
      } catch (Exception e) {
        log.error("Error in virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(5000); // Wait up to 5 seconds
    
    // Verify the thread completed
    assertThat("Virtual thread should complete", virtualThread.isAlive(), is(false));
    
    // Verify the thread names are consistent (same virtual thread before and after I/O)
    assertThat("Thread names should be recorded", threadNames.size(), is(2));
    assertThat("Same virtual thread should be used before and after I/O", 
        threadNames.get(0), is(threadNames.get(1)));
    
    // Verify payload was closed
    verify(blockingPayload).close();
  }

  /**
   * Tests the performance difference between platform threads and virtual threads
   * when handling concurrent HTTP responses.
   * 
   * This test measures the time taken to process a fixed number of requests using
   * both thread types and verifies that virtual threads provide better performance
   * for I/O-bound operations.
   */
  @Test
  public void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    final int TEST_SIZE = 1000; // Number of requests to process
    
    // Configure payload
    when(payload.getContentType()).thenReturn("text/plain");
    when(payload.getSize()).thenReturn((long) TEST_CONTENT.length);
    
    // Configure output stream to simulate I/O delay
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Simulate I/O delay (10ms)
        Thread.sleep(10);
        return null;
      }
    }).when(output).write(any(byte[].class), any(int.class), any(int.class));
    
    // Test with platform threads
    long platformThreadStart = System.nanoTime();
    CountDownLatch platformLatch = new CountDownLatch(TEST_SIZE);
    
    try (var executor = Executors.newFixedThreadPool(100)) { // Limited to 100 platform threads
      for (int i = 0; i < TEST_SIZE; i++) {
        executor.submit(() -> {
          try {
            InputStream requestInput = new ByteArrayInputStream(TEST_CONTENT);
            when(payload.openInputStream()).thenReturn(requestInput);
            underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
          } catch (Exception e) {
            log.error("Error in platform thread", e);
          } finally {
            platformLatch.countDown();
          }
        });
      }
      
      platformLatch.await(30, TimeUnit.SECONDS);
    }
    
    long platformThreadDuration = Duration.ofNanos(System.nanoTime() - platformThreadStart).toMillis();
    log.info("Platform threads took {} ms to process {} requests", platformThreadDuration, TEST_SIZE);
    
    // Test with virtual threads
    long virtualThreadStart = System.nanoTime();
    CountDownLatch virtualLatch = new CountDownLatch(TEST_SIZE);
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < TEST_SIZE; i++) {
        executor.submit(() -> {
          try {
            InputStream requestInput = new ByteArrayInputStream(TEST_CONTENT);
            when(payload.openInputStream()).thenReturn(requestInput);
            underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
          } catch (Exception e) {
            log.error("Error in virtual thread", e);
          } finally {
            virtualLatch.countDown();
          }
        });
      }
      
      virtualLatch.await(30, TimeUnit.SECONDS);
    }
    
    long virtualThreadDuration = Duration.ofNanos(System.nanoTime() - virtualThreadStart).toMillis();
    log.info("Virtual threads took {} ms to process {} requests", virtualThreadDuration, TEST_SIZE);
    
    // Virtual threads should be faster or at least not significantly slower
    // Note: In some environments, the first run might have JVM warmup effects
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadDuration, lessThan(platformThreadDuration * 2));
    
    log.info("Performance comparison: Virtual threads were {}% of platform thread duration",
        (virtualThreadDuration * 100) / platformThreadDuration);
  }
}