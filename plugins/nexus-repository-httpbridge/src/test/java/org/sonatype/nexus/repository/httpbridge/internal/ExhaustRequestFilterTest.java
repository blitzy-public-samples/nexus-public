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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationMode;

import static org.apache.http.HttpHeaders.USER_AGENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.PUT;
import static org.sonatype.nexus.repository.http.HttpStatus.BAD_REQUEST;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;

/**
 * Tests for {@link ExhaustRequestFilter}
 */
public class ExhaustRequestFilterTest extends TestSupport
{
  private static final String PIPE_DELIMITED_MATCHING_PATTERN = "Apache-Maven.*|Apache Ivy.*";

  private static final String UNFORTUNATELY_SUPPORTED_COMMA_DELIMITED_MATCHING_PATTERN =
      "Apache-Maven.*\\s,\\sApache Ivy.*";

  private static final String NULL_USER_AGENT = null;

  @Mock
  HttpServletRequest request;

  @Mock
  HttpServletResponse response;

  @Mock
  javax.servlet.FilterChain filterChain;

  @Test
  public void httpOkResponse() throws Exception {
    verifyRequestNotExhausted(OK, PUT, "Apache-Maven.Foo");
  }

  @Test
  public void http400GetResponse() throws Exception {
    verifyRequestNotExhausted(BAD_REQUEST, GET, "Apache-Maven.Foo");
  }

  @Test
  public void http400PutResponse_NullUserAgent() throws Exception {
    verifyRequestNotExhausted(BAD_REQUEST, PUT, NULL_USER_AGENT);
  }

  @Test
  public void http400PutResponse_NonMavenOrIvyUserAgent() throws Exception {
    verifyRequestNotExhausted(BAD_REQUEST, PUT, "notmavenorivy");
  }

  @Test
  public void http400PutResponse_MavenUserAgent() throws Exception {
    verifyRequestExhausted(BAD_REQUEST, PUT, "Apache-Maven.Foo");
  }

  @Test
  public void http400PutResponse_IvyUserAgent() throws Exception {
    verifyRequestExhausted(BAD_REQUEST, PUT, "Apache Ivy.Foo");
  }

  @Test
  public void virtualThreadExhaustionTest() throws Exception {
    // Test that the filter works correctly when executed in a Virtual Thread
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        try {
          verifyRequestExhausted(BAD_REQUEST, PUT, "Apache-Maven.Foo");
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      future.get(); // Wait for completion and propagate any exceptions
    }
  }

  @Test
  public void concurrentRequestHandlingTest() throws Exception {
    // Test filter behavior under high concurrent load
    final int concurrentRequests = 50;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(concurrentRequests);
    final AtomicInteger successCount = new AtomicInteger(0);
    final List<Exception> exceptions = new ArrayList<>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < concurrentRequests; i++) {
        final int requestId = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Setup a mock request with a large input stream to simulate real-world conditions
            setupMockResponse(BAD_REQUEST, PUT, "Apache-Maven.Foo");
            setupMockInputStream(1024 * 10); // 10KB of data
            
            // Execute the filter
            new ExhaustRequestFilter(PIPE_DELIMITED_MATCHING_PATTERN).doFilter(request, response, filterChain);
            
            // Verify the request was exhausted
            verify(request, times(1)).getInputStream();
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(new Exception("Request " + requestId + " failed", e));
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all requests to complete
      completionLatch.await();
    }

    // Verify all requests were processed successfully
    if (!exceptions.isEmpty()) {
      throw new AssertionError("Encountered " + exceptions.size() + " exceptions: " + exceptions.get(0), exceptions.get(0));
    }
    assertEquals("All requests should be processed successfully", concurrentRequests, successCount.get());
  }

  @Test
  public void threadPinningTest() throws Exception {
    // Test that the filter handles thread pinning scenarios correctly
    // Thread pinning occurs when a Virtual Thread is forced to execute on a platform thread
    // due to blocking operations that cannot be optimized by the runtime
    
    // Setup a mock request with a special input stream that simulates a blocking operation
    setupMockResponse(BAD_REQUEST, PUT, "Apache-Maven.Foo");
    setupBlockingInputStream();
    
    // Execute the filter in a Virtual Thread
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        try {
          new ExhaustRequestFilter(PIPE_DELIMITED_MATCHING_PATTERN).doFilter(request, response, filterChain);
          verify(request, times(1)).getInputStream();
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      future.get(); // Wait for completion and propagate any exceptions
    }
  }

  @Test
  public void largeRequestBodyExhaustionTest() throws Exception {
    // Test exhausting a large request body
    setupMockResponse(BAD_REQUEST, PUT, "Apache-Maven.Foo");
    setupMockInputStream(1024 * 1024 * 5); // 5MB of data
    
    // Execute in a Virtual Thread to test efficient I/O handling
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        try {
          new ExhaustRequestFilter(PIPE_DELIMITED_MATCHING_PATTERN).doFilter(request, response, filterChain);
          verify(request, times(1)).getInputStream();
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      future.get(); // Wait for completion and propagate any exceptions
    }
  }

  private void verifyRequestNotExhausted(final Integer status, final String method, final String userAgent)
      throws Exception
  {
    verifyRequestExhaustionStatus(status, method, userAgent, never());
  }

  private void verifyRequestExhausted(final Integer status, final String method, final String userAgent)
      throws Exception
  {
    verifyRequestExhaustionStatus(status, method, userAgent, times(1));
  }

  private void verifyRequestExhaustionStatus(final Integer status, final String method, final String userAgent,
                                             final VerificationMode verificationMode) throws Exception
  {
    doVerifyRequestExhaustionStatus(status, method, userAgent, PIPE_DELIMITED_MATCHING_PATTERN, verificationMode);
    doVerifyRequestExhaustionStatus(
        status, method, userAgent, UNFORTUNATELY_SUPPORTED_COMMA_DELIMITED_MATCHING_PATTERN, verificationMode);
  }

  private void doVerifyRequestExhaustionStatus(final Integer status, final String method, final String userAgent,
                                               final String exhaustForAgents, final VerificationMode verificationMode)
      throws Exception
  {
    setupMockResponse(status, method, userAgent);
    new ExhaustRequestFilter(exhaustForAgents).doFilter(request, response, filterChain);
    verify(request, verificationMode).getInputStream();
  }

  private void setupMockResponse(final Integer status, final String method, final String userAgent) {
    reset(request, response);
    when(response.getStatus()).thenReturn(status);
    when(request.getMethod()).thenReturn(method);
    when(request.getHeader(eq(USER_AGENT))).thenReturn(userAgent);
  }

  private void setupMockInputStream(final int size) throws IOException {
    // Create a byte array of the specified size filled with test data
    byte[] data = new byte[size];
    for (int i = 0; i < size; i++) {
      data[i] = (byte) (i % 256);
    }
    
    // Create a ServletInputStream that reads from the byte array
    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
    ServletInputStream servletInputStream = new DelegatingServletInputStream(byteArrayInputStream);
    
    when(request.getInputStream()).thenReturn(servletInputStream);
  }

  private void setupBlockingInputStream() throws IOException {
    // Create a ServletInputStream that simulates blocking I/O operations
    ServletInputStream blockingStream = new ServletInputStream() {
      private int readCount = 0;
      private final int maxReads = 1000; // Number of reads before EOF
      
      @Override
      public int read() throws IOException {
        // Simulate a blocking operation by sleeping briefly
        try {
          Thread.sleep(1);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted during read", e);
        }
        
        if (readCount++ < maxReads) {
          return readCount % 256; // Return some data
        }
        return -1; // EOF
      }
    };
    
    when(request.getInputStream()).thenReturn(blockingStream);
  }

  /**
   * Simple ServletInputStream implementation that delegates to a ByteArrayInputStream.
   */
  private static class DelegatingServletInputStream extends ServletInputStream {
    private final InputStream delegate;

    DelegatingServletInputStream(InputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
      return delegate.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
      return delegate.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return delegate.read(b, off, len);
    }
  }
}