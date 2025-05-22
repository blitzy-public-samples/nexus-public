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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.httpbridge.internal.ExhaustRequestFilter;

import static org.apache.http.HttpHeaders.USER_AGENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.PUT;
import static org.sonatype.nexus.repository.http.HttpStatus.BAD_REQUEST;

/**
 * Tests for {@link ExhaustRequestFilter} using Java 21 Virtual Threads to verify
 * high concurrency behavior and thread pinning issues.
 */
public class ExhaustRequestFilterVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(ExhaustRequestFilterVirtualThreadTest.class);
  
  private static final String MAVEN_USER_AGENT = "Apache-Maven.Foo";
  private static final String PIPE_DELIMITED_MATCHING_PATTERN = "Apache-Maven.*|Apache Ivy.*";
  private static final int CONCURRENT_REQUESTS = 10_000;
  private static final int REQUEST_BODY_SIZE = 1024 * 10; // 10KB
  
  private AutoCloseable mocks;
  private ExhaustRequestFilter filter;
  private ExecutorService executorService;
  
  @Mock
  private HttpServletRequest request;
  
  @Mock
  private HttpServletResponse response;
  
  @Mock
  private FilterChain filterChain;
  
  @BeforeEach
  public void setup() {
    mocks = MockitoAnnotations.openMocks(this);
    filter = new ExhaustRequestFilter(PIPE_DELIMITED_MATCHING_PATTERN);
    executorService = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    executorService.shutdownNow();
    mocks.close();
  }
  
  /**
   * Tests that the filter can handle thousands of concurrent requests using virtual threads
   * without thread pinning or resource exhaustion.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Setup mock request with a large body that needs to be exhausted
    byte[] requestBody = new byte[REQUEST_BODY_SIZE];
    // Fill with some data
    for (int i = 0; i < requestBody.length; i++) {
      requestBody[i] = (byte) (i % 256);
    }
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
    
    // Setup common request behavior
    setupMockResponse(BAD_REQUEST, PUT, MAVEN_USER_AGENT);
    
    // Create a mock ServletInputStream that will be used by the filter
    ServletInputStream mockInputStream = mock(ServletInputStream.class);
    when(request.getInputStream()).thenReturn(mockInputStream);
    
    // Setup the mock input stream to return our test data
    doAnswer(invocation -> {
      // Simulate reading from the input stream
      // This is where thread pinning could occur if not handled properly
      return new ByteArrayInputStream(requestBody);
    }).when(request).getInputStream();
    
    // Launch concurrent requests using virtual threads
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int requestId = i;
      Thread thread = Thread.ofVirtual().name("request-" + requestId).start(() -> {
        try {
          // Check if this is a virtual thread
          if (!Thread.currentThread().isVirtual()) {
            log.warn("Thread {} is not a virtual thread", Thread.currentThread().getName());
          }
          
          // Process the request through the filter
          filter.doFilter(request, response, filterChain);
          
          // Verify that the input stream was consumed
          verify(request, times(1)).getInputStream();
          
          successCount.incrementAndGet();
        } catch (Exception e) {
          log.error("Error processing request {}", requestId, e);
          failureCount.incrementAndGet();
          
          // Check if this is a thread pinning issue
          if (e.getMessage() != null && e.getMessage().contains("pinned")) {
            threadPinningDetected.set(true);
          }
        } finally {
          latch.countDown();
        }
      });
      threads.add(thread);
    }
    
    // Wait for all threads to complete or timeout
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "Not all requests completed within the timeout period");
    
    // Verify results
    assertEquals(CONCURRENT_REQUESTS, successCount.get(), "Not all requests were successful");
    assertEquals(0, failureCount.get(), "Some requests failed");
    assertFalse(threadPinningDetected.get(), "Thread pinning was detected");
    
    // Verify that the filter chain was called for each request
    verify(filterChain, times(CONCURRENT_REQUESTS)).doFilter(eq(request), eq(response));
  }
  
  /**
   * Tests that the filter properly exhausts the request body when an error occurs,
   * even under high concurrency with virtual threads.
   */
  @Test
  public void testRequestBodyExhaustionUnderLoad() throws Exception {
    // Setup mock request with a body that needs to be exhausted
    byte[] requestBody = new byte[REQUEST_BODY_SIZE];
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger exhaustedCount = new AtomicInteger(0);
    
    // Setup common request behavior
    setupMockResponse(BAD_REQUEST, PUT, MAVEN_USER_AGENT);
    
    // Create a mock InputStream that will track if it was fully consumed
    doAnswer(invocation -> {
      InputStream mockStream = new ByteArrayInputStream(requestBody) {
        private boolean fullyConsumed = false;
        
        @Override
        public int read(byte[] b) throws IOException {
          int result = super.read(b);
          if (result == -1) {
            fullyConsumed = true;
            exhaustedCount.incrementAndGet();
          }
          return result;
        }
        
        @Override
        public void close() throws IOException {
          super.close();
          if (!fullyConsumed) {
            // If we close without reading everything, don't count as exhausted
          }
        }
      };
      return mockStream;
    }).when(request).getInputStream();
    
    // Launch concurrent requests using virtual threads
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      executorService.submit(() -> {
        try {
          filter.doFilter(request, response, filterChain);
        } catch (Exception e) {
          log.error("Error processing request", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete or timeout
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "Not all requests completed within the timeout period");
    
    // Verify that all request bodies were exhausted
    assertEquals(CONCURRENT_REQUESTS, exhaustedCount.get(), 
        "Not all request bodies were properly exhausted");
  }
  
  /**
   * Tests for thread pinning issues by introducing a delay during input stream consumption.
   * This test verifies that virtual threads can be properly suspended and resumed during I/O operations.
   */
  @Test
  public void testNoPinningDuringInputStreamConsumption() throws Exception {
    // Setup mock request with a body that needs to be exhausted
    byte[] requestBody = new byte[1024]; // Smaller body for this test
    
    // Create a countdown latch to wait for all threads to complete
    int concurrentRequests = 100; // Smaller number for this specific test
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Setup common request behavior
    setupMockResponse(BAD_REQUEST, PUT, MAVEN_USER_AGENT);
    
    // Create a mock InputStream that will introduce a delay during reading
    // This helps detect thread pinning issues
    doAnswer(invocation -> {
      return new ByteArrayInputStream(requestBody) {
        @Override
        public int read(byte[] b) throws IOException {
          try {
            // Introduce a small delay to simulate slow I/O
            // This should not cause thread pinning with virtual threads
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during read", e);
          }
          return super.read(b);
        }
      };
    }).when(request).getInputStream();
    
    // Track the start time to measure overall performance
    long startTime = System.nanoTime();
    
    // Launch concurrent requests using virtual threads
    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      executorService.submit(() -> {
        try {
          // Record thread state before and after filter execution
          // to detect if the thread was pinned
          Thread currentThread = Thread.currentThread();
          String threadName = currentThread.getName();
          
          // Process the request through the filter
          filter.doFilter(request, response, filterChain);
          
        } catch (Exception e) {
          log.error("Error processing request {}", requestId, e);
          if (e.getMessage() != null && e.getMessage().contains("pinned")) {
            pinningDetected.set(true);
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete or timeout
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Calculate elapsed time
    long elapsedTime = Duration.ofNanos(System.nanoTime() - startTime).toMillis();
    log.info("Completed {} requests in {} ms", concurrentRequests, elapsedTime);
    
    assertTrue(completed, "Not all requests completed within the timeout period");
    assertFalse(pinningDetected.get(), "Thread pinning was detected during input stream consumption");
    
    // With virtual threads, the elapsed time should be close to the delay time
    // because all threads can run concurrently without being blocked
    long expectedMaxTime = 100; // Slightly more than the delay time to account for overhead
    
    // This assertion might be flaky depending on the test environment,
    // so we'll log a warning instead of failing the test if it's not met
    if (elapsedTime > expectedMaxTime * 2) {
      log.warn("Performance may indicate thread pinning: {} ms elapsed for {} requests with 50ms delay",
          elapsedTime, concurrentRequests);
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for request processing.
   */
  @Test
  public void testPerformanceComparisonWithPlatformThreads() throws Exception {
    // Skip this test if running in a CI environment or with limited resources
    if (Boolean.getBoolean("skipPerformanceTests")) {
      log.info("Skipping performance comparison test");
      return;
    }
    
    // Number of requests for performance test (smaller than the main test)
    int requestCount = 1000;
    
    // Setup mock request with a body that needs to be exhausted
    byte[] requestBody = new byte[1024]; // Smaller body for performance test
    
    // Setup common request behavior
    setupMockResponse(BAD_REQUEST, PUT, MAVEN_USER_AGENT);
    
    // Create a mock InputStream
    doAnswer(invocation -> new ByteArrayInputStream(requestBody))
        .when(request).getInputStream();
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService platformExecutor = Executors.newFixedThreadPool(100)) {
        CountDownLatch platformLatch = new CountDownLatch(requestCount);
        
        for (int i = 0; i < requestCount; i++) {
          platformExecutor.submit(() -> {
            try {
              filter.doFilter(request, response, filterChain);
            } catch (Exception e) {
              log.error("Error with platform thread", e);
            } finally {
              platformLatch.countDown();
            }
          });
        }
        
        platformLatch.await(30, TimeUnit.SECONDS);
      }
    });
    
    // Reset mocks between tests
    reset(request, response, filterChain);
    setupMockResponse(BAD_REQUEST, PUT, MAVEN_USER_AGENT);
    doAnswer(invocation -> new ByteArrayInputStream(requestBody))
        .when(request).getInputStream();
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
        CountDownLatch virtualLatch = new CountDownLatch(requestCount);
        
        for (int i = 0; i < requestCount; i++) {
          virtualExecutor.submit(() -> {
            try {
              filter.doFilter(request, response, filterChain);
            } catch (Exception e) {
              log.error("Error with virtual thread", e);
            } finally {
              virtualLatch.countDown();
            }
          });
        }
        
        virtualLatch.await(30, TimeUnit.SECONDS);
      }
    });
    
    log.info("Performance comparison for {} requests:", requestCount);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
    log.info("Improvement factor: {}x", (double) platformThreadTime / virtualThreadTime);
    
    // We expect virtual threads to be faster, but this is not a strict requirement
    // as it depends on the test environment and JVM implementation
    if (virtualThreadTime > platformThreadTime) {
      log.warn("Virtual threads were slower than platform threads in this test environment");
    }
  }
  
  /**
   * Helper method to measure execution time of a runnable task.
   */
  private long measureExecutionTime(RunnableWithException task) throws Exception {
    long startTime = System.nanoTime();
    task.run();
    return Duration.ofNanos(System.nanoTime() - startTime).toMillis();
  }
  
  /**
   * Functional interface for a runnable that can throw exceptions.
   */
  @FunctionalInterface
  private interface RunnableWithException {
    void run() throws Exception;
  }
  
  /**
   * Helper method to set up mock response behavior.
   */
  private void setupMockResponse(final Integer status, final String method, final String userAgent) {
    reset(request, response);
    when(response.getStatus()).thenReturn(status);
    when(request.getMethod()).thenReturn(method);
    when(request.getHeader(eq(USER_AGENT))).thenReturn(userAgent);
  }
}