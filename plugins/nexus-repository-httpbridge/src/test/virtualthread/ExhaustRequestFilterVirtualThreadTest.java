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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.mockito.MockitoAnnotations;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.httpbridge.internal.ExhaustRequestFilter;
import org.sonatype.nexus.testsuite.testsupport.ThreadPinningDetector;

import static org.apache.http.HttpHeaders.USER_AGENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.PUT;
import static org.sonatype.nexus.repository.http.HttpStatus.BAD_REQUEST;

/**
 * Tests for {@link ExhaustRequestFilter} with Java 21 Virtual Threads.
 * 
 * This test validates that the ExhaustRequestFilter component works correctly with
 * high concurrency using Virtual Threads, ensuring that request body exhaustion
 * functions properly without thread pinning or resource exhaustion issues.
 */
public class ExhaustRequestFilterVirtualThreadTest extends TestSupport
{
  private static final String MAVEN_USER_AGENT = "Apache-Maven/3.9.5";
  private static final String EXHAUSTING_PATTERN = "Apache-Maven.*|Apache Ivy.*";
  private static final int CONCURRENT_REQUESTS = 5000;
  private static final int REQUEST_BODY_SIZE = 1024 * 10; // 10KB
  
  private AutoCloseable mocks;
  
  @Mock
  private HttpServletRequest request;
  
  @Mock
  private HttpServletResponse response;
  
  @Mock
  private FilterChain filterChain;
  
  @Mock
  private ServletInputStream servletInputStream;
  
  private ExhaustRequestFilter filter;
  private byte[] requestBody;
  private ThreadPinningDetector pinningDetector;
  
  @BeforeEach
  public void setup() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    filter = new ExhaustRequestFilter(EXHAUSTING_PATTERN);
    requestBody = new byte[REQUEST_BODY_SIZE];
    pinningDetector = new ThreadPinningDetector();
    
    // Fill request body with random data
    for (int i = 0; i < REQUEST_BODY_SIZE; i++) {
      requestBody[i] = (byte) (Math.random() * 256);
    }
  }
  
  @AfterEach
  public void cleanup() throws Exception {
    mocks.close();
  }
  
  /**
   * Tests that the ExhaustRequestFilter correctly exhausts request bodies
   * when using Virtual Threads with high concurrency.
   */
  @Test
  public void testHighConcurrencyExhaustion() throws Exception {
    // Setup request to return a 400 error and a Maven user agent
    when(response.getStatus()).thenReturn(BAD_REQUEST);
    when(request.getMethod()).thenReturn(PUT);
    when(request.getHeader(eq(USER_AGENT))).thenReturn(MAVEN_USER_AGENT);
    
    // Setup mock input stream to track exhaustion
    final AtomicInteger bytesRead = new AtomicInteger(0);
    final CountDownLatch exhaustionLatch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    when(request.getInputStream()).thenReturn(servletInputStream);
    doAnswer(invocation -> {
      // Create a new input stream for each request to simulate real behavior
      InputStream is = new ByteArrayInputStream(requestBody) {
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          // Detect if we're running in a virtual thread
          boolean isVirtualThread = Thread.currentThread().isVirtual();
          assertTrue(isVirtualThread, "Test should be running in a virtual thread");
          
          // Check for thread pinning during I/O operations
          pinningDetector.checkForPinning();
          
          int result = super.read(b, off, len);
          if (result > 0) {
            bytesRead.addAndGet(result);
          } else if (result == -1) {
            // End of stream reached, mark this request as exhausted
            exhaustionLatch.countDown();
          }
          return result;
        }
      };
      return is;
    }).when(servletInputStream).read(eq(-1));
    
    // Execute filter with many concurrent virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        executor.submit(() -> {
          try {
            filter.doFilter(request, response, filterChain);
          } catch (IOException | ServletException e) {
            throw new RuntimeException(e);
          }
        });
      }
      
      // Wait for all requests to be exhausted or timeout
      boolean allExhausted = exhaustionLatch.await(30, TimeUnit.SECONDS);
      assertTrue(allExhausted, "Not all requests were exhausted within the timeout period");
      
      // Verify that all bytes were read
      assertEquals(REQUEST_BODY_SIZE * CONCURRENT_REQUESTS, bytesRead.get(), 
          "Not all request body bytes were exhausted");
      
      // Verify no thread pinning was detected
      assertFalse(pinningDetector.wasPinningDetected(), 
          "Thread pinning detected during request exhaustion: " + pinningDetector.getPinningDetails());
    }
  }
  
  /**
   * Tests the performance difference between platform threads and virtual threads
   * when exhausting request bodies.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    // Setup request to return a 400 error and a Maven user agent
    when(response.getStatus()).thenReturn(BAD_REQUEST);
    when(request.getMethod()).thenReturn(PUT);
    when(request.getHeader(eq(USER_AGENT))).thenReturn(MAVEN_USER_AGENT);
    
    // Setup mock input stream
    when(request.getInputStream()).thenReturn(servletInputStream);
    doAnswer(invocation -> new ByteArrayInputStream(requestBody)).when(servletInputStream).read(eq(-1));
    
    // Test with platform threads
    long platformThreadStart = System.currentTimeMillis();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(200)) { // Limited thread pool
      CountDownLatch platformLatch = new CountDownLatch(CONCURRENT_REQUESTS);
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        platformExecutor.submit(() -> {
          try {
            filter.doFilter(request, response, filterChain);
            platformLatch.countDown();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
      
      // Wait for completion or timeout
      platformLatch.await(30, TimeUnit.SECONDS);
    }
    long platformThreadTime = System.currentTimeMillis() - platformThreadStart;
    
    // Test with virtual threads
    long virtualThreadStart = System.currentTimeMillis();
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch virtualLatch = new CountDownLatch(CONCURRENT_REQUESTS);
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        virtualExecutor.submit(() -> {
          try {
            filter.doFilter(request, response, filterChain);
            virtualLatch.countDown();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
      
      // Wait for completion or timeout
      virtualLatch.await(30, TimeUnit.SECONDS);
    }
    long virtualThreadTime = System.currentTimeMillis() - virtualThreadStart;
    
    // Log performance comparison
    log.info("Performance comparison for {} concurrent requests:", CONCURRENT_REQUESTS);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
    log.info("Improvement factor: {}x", (double) platformThreadTime / virtualThreadTime);
    
    // We expect virtual threads to be faster, but don't assert on exact timing
    // as it depends on the test environment
  }
  
  /**
   * Tests that the ExhaustRequestFilter correctly handles resource cleanup
   * when using Virtual Threads, even with error conditions.
   */
  @Test
  public void testResourceCleanupWithErrors() throws Exception {
    // Setup request to return a 400 error and a Maven user agent
    when(response.getStatus()).thenReturn(BAD_REQUEST);
    when(request.getMethod()).thenReturn(PUT);
    when(request.getHeader(eq(USER_AGENT))).thenReturn(MAVEN_USER_AGENT);
    
    // Setup mock input stream to throw an exception during read
    final AtomicInteger streamClosedCount = new AtomicInteger(0);
    
    when(request.getInputStream()).thenReturn(servletInputStream);
    doAnswer(invocation -> {
      return new ByteArrayInputStream(requestBody) {
        @Override
        public int read() throws IOException {
          // Simulate an I/O error during read
          throw new IOException("Simulated I/O error");
        }
        
        @Override
        public void close() throws IOException {
          streamClosedCount.incrementAndGet();
          super.close();
        }
      };
    }).when(servletInputStream).read(eq(-1));
    
    // Execute filter with concurrent virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        executor.submit(() -> {
          try {
            filter.doFilter(request, response, filterChain);
          } catch (Exception e) {
            // Expected exception, ignore
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Not all requests completed within the timeout period");
      
      // Verify that all streams were closed despite errors
      assertEquals(CONCURRENT_REQUESTS, streamClosedCount.get(), 
          "Not all input streams were properly closed");
    }
  }
}