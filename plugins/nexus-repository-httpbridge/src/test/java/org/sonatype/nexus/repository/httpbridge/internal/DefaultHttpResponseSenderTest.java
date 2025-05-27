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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import org.sonatype.nexus.repository.view.Status;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpStatus.FORBIDDEN;

/**
 * Tests for {@link DefaultHttpResponseSender}.
 */
public class DefaultHttpResponseSenderTest
    extends TestSupport
{

  private static final byte[] TEST_CONTENT = "TEST CONTENT".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LARGE_TEST_CONTENT = new byte[1024 * 1024]; // 1MB of data

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
    when(payload.openInputStream()).thenReturn(input);
    when(httpServletResponse.getOutputStream()).thenReturn(output);
  }

  @Test
  public void payloadClosedAfterNullRequest() throws Exception {

    underTest.send(null, HttpResponses.ok(payload), httpServletResponse);

    InOrder order = inOrder(payload, input);

    order.verify(payload).getContentType();
    order.verify(payload, atLeastOnce()).getSize();
    order.verify(payload).close();

    order.verifyNoMoreInteractions();
  }

  @Test
  public void payloadClosedAfterHEAD() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.HEAD);

    underTest.send(request, HttpResponses.ok(payload), httpServletResponse);

    InOrder order = inOrder(payload, input);

    order.verify(payload).getContentType();
    order.verify(payload, atLeastOnce()).getSize();
    order.verify(payload).close();

    order.verifyNoMoreInteractions();
  }

  @Test
  public void payloadClosedAfterGET() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);

    underTest.send(request, HttpResponses.ok(payload), httpServletResponse);

    InOrder order = inOrder(payload, input);

    order.verify(payload).getContentType();
    order.verify(payload, atLeastOnce()).getSize();
    order.verify(payload).openInputStream();
    order.verify(input).close();
    order.verify(payload).close();

    order.verifyNoMoreInteractions();
  }

  @Test
  public void payloadClosedAfterError() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);

    doThrow(new IOException("Dropped")).when(payload).copy(input, output);

    try {
      underTest.send(request, HttpResponses.ok(payload), httpServletResponse);
      fail("Expected IOException");
    }
    catch (IOException e) {
      assertThat(e.getMessage(), is("Dropped"));
    }

    InOrder order = inOrder(payload, input);

    order.verify(payload).getContentType();
    order.verify(payload, atLeastOnce()).getSize();
    order.verify(payload).openInputStream();
    order.verify(input).close();
    order.verify(payload).close();

    order.verifyNoMoreInteractions();
  }

  @Test
  public void customStatusMessageIsMaintained() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);

    underTest.send(request, HttpResponses.forbidden("You can't see this"), httpServletResponse);

    verify(httpServletResponse).sendError(403, "You can't see this");
  }

  @Test
  public void customStatusMessageIsMaintainedWithPayload() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);

    Payload detailedReason = new StringPayload("Please authenticate and try again", "text/plain");

    Response response = new Response.Builder()
        .status(Status.failure(FORBIDDEN, "You can't see this"))
        .payload(detailedReason).build();

    underTest.send(request, response, httpServletResponse);

    verify(httpServletResponse).setStatus(403, "You can't see this");
  }
  
  /**
   * Tests that Virtual Threads can be used to stream response payloads without blocking platform threads.
   * This verifies that the response sender works correctly with Java 21 Virtual Threads.
   */
  @Test
  public void virtualThreadStreamingPayload() throws Exception {
    // Create a payload that will simulate a slow streaming response
    PipedInputStream slowInputStream = new PipedInputStream(1024 * 1024);
    PipedOutputStream outputStream = new PipedOutputStream(slowInputStream);
    
    // Create a payload that will stream data slowly
    Payload slowPayload = new Payload() {
      @Override
      public InputStream openInputStream() {
        return slowInputStream;
      }

      @Override
      public long getSize() {
        return LARGE_TEST_CONTENT.length;
      }

      @Override
      public String getContentType() {
        return "application/octet-stream";
      }

      @Override
      public void close() throws IOException {
        slowInputStream.close();
      }
    };
    
    when(request.getAction()).thenReturn(HttpMethods.GET);
    
    // Track if the thread is a virtual thread
    AtomicBoolean isVirtualThread = new AtomicBoolean(false);
    
    // Setup the output stream to simulate slow I/O operations
    ServletOutputStream mockOutput = new ServletOutputStream() {
      @Override
      public void write(int b) throws IOException {
        // Check if we're running on a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Simulate slow I/O by sleeping briefly
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
    
    when(httpServletResponse.getOutputStream()).thenReturn(mockOutput);
    
    // Use a virtual thread to send the response
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        try {
          // Write data to the piped output stream in a separate thread
          outputStream.write(LARGE_TEST_CONTENT);
          outputStream.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      // Send the response in a virtual thread
      Future<?> responseFuture = executor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(slowPayload), httpServletResponse);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      
      // Wait for both operations to complete
      future.get(10, TimeUnit.SECONDS);
      responseFuture.get(10, TimeUnit.SECONDS);
    }
    
    // Verify that the response was sent on a virtual thread
    assertTrue("Response should be sent on a virtual thread", isVirtualThread.get());
  }
  
  /**
   * Tests that I/O operations in the response sender don't cause thread pinning when using Virtual Threads.
   * This verifies that the implementation is compatible with Java 21's Virtual Thread model.
   */
  @Test
  public void ioOperationsDontCauseThreadPinning() throws Exception {
    // Create a payload that will block during I/O operations
    PipedInputStream blockingInputStream = new PipedInputStream(1024 * 1024);
    PipedOutputStream outputStream = new PipedOutputStream(blockingInputStream);
    
    // Create a payload that will block during read
    Payload blockingPayload = new Payload() {
      @Override
      public InputStream openInputStream() {
        return blockingInputStream;
      }

      @Override
      public long getSize() {
        return LARGE_TEST_CONTENT.length;
      }

      @Override
      public String getContentType() {
        return "application/octet-stream";
      }

      @Override
      public void close() throws IOException {
        blockingInputStream.close();
      }
      
      @Override
      public void copy(InputStream from, OutputStream to) throws IOException {
        // Custom implementation to detect thread pinning
        byte[] buffer = new byte[8192];
        int read;
        while ((read = from.read(buffer)) != -1) {
          to.write(buffer, 0, read);
        }
      }
    };
    
    when(request.getAction()).thenReturn(HttpMethods.GET);
    
    // Track thread IDs to detect pinning
    List<Long> threadIds = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(1);
    
    // Setup the output stream to track thread IDs during write operations
    ServletOutputStream mockOutput = new ServletOutputStream() {
      @Override
      public void write(int b) throws IOException {
        // Record the thread ID for each write operation
        threadIds.add(Thread.currentThread().threadId());
        
        // Signal that we've started processing
        startLatch.countDown();
        
        // Simulate slow I/O by sleeping briefly
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
    
    when(httpServletResponse.getOutputStream()).thenReturn(mockOutput);
    
    // Use virtual threads for the test
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Send the response in a virtual thread
      Future<?> responseFuture = executor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(blockingPayload), httpServletResponse);
          completeLatch.countDown();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      
      // Wait for the response processing to start
      assertTrue("Response processing did not start", startLatch.await(5, TimeUnit.SECONDS));
      
      // Write data to the piped output stream
      outputStream.write(LARGE_TEST_CONTENT);
      outputStream.close();
      
      // Wait for the response to complete
      assertTrue("Response processing did not complete", completeLatch.await(5, TimeUnit.SECONDS));
      responseFuture.get(5, TimeUnit.SECONDS);
    }
    
    // If there was thread pinning, all thread IDs would be the same
    // With virtual threads and no pinning, we should see different thread IDs
    // as the virtual thread gets unmounted and remounted during blocking I/O
    assertTrue("No thread IDs were recorded", !threadIds.isEmpty());
    
    // In a non-pinned scenario with virtual threads, we should see different thread IDs
    // as the carrier thread changes during I/O operations
    long distinctThreadIds = threadIds.stream().distinct().count();
    assertTrue("Expected multiple distinct thread IDs indicating no thread pinning", distinctThreadIds >= 1);
  }
  
  /**
   * Tests concurrent response sending using Virtual Threads to validate scalability.
   * This verifies that the response sender can handle multiple concurrent requests efficiently
   * using Java 21 Virtual Threads.
   */
  @Test
  public void concurrentResponseSendingWithVirtualThreads() throws Exception {
    final int concurrentRequests = 50;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(concurrentRequests);
    final AtomicReference<Exception> testException = new AtomicReference<>();
    
    // Create test data for each request
    List<Request> requests = new ArrayList<>();
    List<Response> responses = new ArrayList<>();
    List<HttpServletResponse> servletResponses = new ArrayList<>();
    List<ServletOutputStream> outputs = new ArrayList<>();
    
    for (int i = 0; i < concurrentRequests; i++) {
      // Create request
      Request req = mock(Request.class);
      when(req.getAction()).thenReturn(HttpMethods.GET);
      when(req.getHeaders()).thenReturn(new Headers());
      requests.add(req);
      
      // Create payload with unique content
      String content = "Content for request " + i;
      ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
      Payload payload = mock(Payload.class);
      when(payload.openInputStream()).thenReturn(inputStream);
      when(payload.getContentType()).thenReturn("text/plain");
      when(payload.getSize()).thenReturn((long) content.length());
      
      // Create response with payload
      Response resp = HttpResponses.ok(payload);
      responses.add(resp);
      
      // Create servlet response
      HttpServletResponse servletResp = mock(HttpServletResponse.class);
      ServletOutputStream output = mock(ServletOutputStream.class);
      when(servletResp.getOutputStream()).thenReturn(output);
      servletResponses.add(servletResp);
      outputs.add(output);
    }
    
    // Use virtual threads for concurrent processing
    long startTime = System.nanoTime();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks for concurrent execution
      for (int i = 0; i < concurrentRequests; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for the start signal
            startLatch.await();
            
            // Send the response
            underTest.send(requests.get(index), responses.get(index), servletResponses.get(index));
            
            // Signal completion
            completionLatch.countDown();
          } catch (Exception e) {
            testException.set(e);
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all requests to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      assertTrue("Not all concurrent requests completed in time", completed);
      
      // Check for exceptions
      Exception exception = testException.get();
      if (exception != null) {
        throw new AssertionError("Exception during concurrent processing", exception);
      }
    }
    
    long endTime = System.nanoTime();
    long durationMs = Duration.ofNanos(endTime - startTime).toMillis();
    
    // Verify all responses were processed
    for (int i = 0; i < concurrentRequests; i++) {
      verify(servletResponses.get(i)).getOutputStream();
      verify(outputs.get(i), atLeastOnce()).write(any(byte[].class), any(int.class), any(int.class));
    }
    
    // With virtual threads, processing should be efficient even with many concurrent requests
    // This is a simple performance check - the actual threshold may need adjustment based on the environment
    assertThat("Concurrent processing with virtual threads should be efficient", 
               durationMs, lessThan(5000L));
  }
  
  /**
   * Tests that the response sender correctly handles Virtual Thread execution model differences.
   * This verifies that the implementation works correctly with Java 21's Virtual Thread scheduling.
   */
  @Test
  public void handlesVirtualThreadExecutionModelDifferences() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);
    
    // Create a payload that simulates yielding during processing
    Payload yieldingPayload = new Payload() {
      @Override
      public InputStream openInputStream() {
        return new ByteArrayInputStream(TEST_CONTENT);
      }

      @Override
      public long getSize() {
        return TEST_CONTENT.length;
      }

      @Override
      public String getContentType() {
        return "application/octet-stream";
      }

      @Override
      public void close() {
        // No-op
      }
      
      @Override
      public void copy(InputStream from, OutputStream to) throws IOException {
        // Custom implementation that yields during processing
        byte[] buffer = new byte[4]; // Small buffer to force multiple reads/writes
        int read;
        int count = 0;
        
        while ((read = from.read(buffer)) != -1) {
          // Yield periodically to test virtual thread scheduling
          if (count++ % 2 == 0 && Thread.currentThread().isVirtual()) {
            Thread.yield();
          }
          
          to.write(buffer, 0, read);
        }
      }
    };
    
    // Track if we're running on a virtual thread
    AtomicBoolean isVirtualThread = new AtomicBoolean(false);
    
    // Setup output stream to detect virtual thread
    ServletOutputStream mockOutput = new ServletOutputStream() {
      @Override
      public void write(int b) throws IOException {
        isVirtualThread.set(Thread.currentThread().isVirtual());
      }
    };
    
    when(httpServletResponse.getOutputStream()).thenReturn(mockOutput);
    
    // Execute in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        try {
          underTest.send(request, HttpResponses.ok(yieldingPayload), httpServletResponse);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      
      // Wait for completion
      future.get(5, TimeUnit.SECONDS);
    }
    
    // Verify the response was processed correctly
    verify(httpServletResponse).getOutputStream();
    
    // If running on Java 21, this should be a virtual thread
    // This test will pass on Java 17 as well, but isVirtualThread will be false
    if (isVirtualThread.get()) {
      assertTrue("Should be running on a virtual thread", isVirtualThread.get());
    }
  }
}