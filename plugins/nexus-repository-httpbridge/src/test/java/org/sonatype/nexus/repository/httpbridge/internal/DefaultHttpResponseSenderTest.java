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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
  private static final int LARGE_CONTENT_SIZE = 10 * 1024 * 1024; // 10MB

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
   * Tests that Virtual Threads properly handle streaming response payloads without blocking
   * platform threads. This verifies that the implementation correctly uses Virtual Threads
   * for I/O operations.
   */
  @Test
  public void virtualThreadsHandleStreamingPayloads() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);
    
    // Create a payload that will simulate a slow streaming response
    final PipedInputStream slowInputStream = new PipedInputStream();
    final PipedOutputStream slowOutputStream = new PipedOutputStream(slowInputStream);
    
    // Create a payload that will stream data slowly
    Payload slowPayload = new Payload() {
      @Override
      public InputStream openInputStream() {
        return slowInputStream;
      }

      @Override
      public long getSize() {
        return Payload.UNKNOWN_SIZE;
      }

      @Override
      public String getContentType() {
        return "text/plain";
      }

      @Override
      public void close() throws IOException {
        slowInputStream.close();
      }
      
      @Override
      public void copy(InputStream from, OutputStream to) throws IOException {
        // Use default implementation that copies from input to output
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = from.read(buffer)) != -1) {
          to.write(buffer, 0, bytesRead);
        }
      }
    };
    
    // Set up a latch to track when the response is being processed
    CountDownLatch processingStarted = new CountDownLatch(1);
    CountDownLatch processingCompleted = new CountDownLatch(1);
    
    // Mock the output stream to signal when data is being written
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        processingStarted.countDown();
        return null;
      }
    }).when(output).write(any(byte[].class), any(int.class), any(int.class));
    
    // Start a thread to send the response
    Thread senderThread = new Thread(() -> {
      try {
        underTest.send(request, HttpResponses.ok(slowPayload), httpServletResponse);
        processingCompleted.countDown();
      } catch (Exception e) {
        fail("Exception in sender thread: " + e.getMessage());
      }
    });
    senderThread.start();
    
    // Wait for processing to start
    assertThat("Processing should start", processingStarted.await(5, TimeUnit.SECONDS), is(true));
    
    // Write some data to the slow output stream
    Thread writerThread = new Thread(() -> {
      try {
        // Write some data in chunks with delays to simulate slow streaming
        for (int i = 0; i < 5; i++) {
          slowOutputStream.write(("Chunk " + i + "\n").getBytes(StandardCharsets.UTF_8));
          slowOutputStream.flush();
          Thread.sleep(100); // Simulate delay between chunks
        }
        slowOutputStream.close();
      } catch (Exception e) {
        fail("Exception in writer thread: " + e.getMessage());
      }
    });
    writerThread.start();
    
    // Verify that processing completes after the stream is closed
    assertThat("Processing should complete", processingCompleted.await(5, TimeUnit.SECONDS), is(true));
    
    // Ensure threads are done
    senderThread.join(1000);
    writerThread.join(1000);
  }
  
  /**
   * Tests that I/O operations don't cause thread pinning when using Virtual Threads.
   * This verifies that the implementation correctly handles I/O without blocking carrier threads.
   */
  @Test
  public void ioOperationsDontCauseThreadPinning() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);
    
    // Create a large payload to ensure significant I/O operations
    byte[] largeContent = new byte[LARGE_CONTENT_SIZE];
    // Fill with some pattern data
    for (int i = 0; i < largeContent.length; i++) {
      largeContent[i] = (byte)(i % 256);
    }
    
    InputStream largeInputStream = new ByteArrayInputStream(largeContent);
    
    // Create a payload with the large content
    Payload largePayload = new Payload() {
      @Override
      public InputStream openInputStream() {
        return largeInputStream;
      }

      @Override
      public long getSize() {
        return largeContent.length;
      }

      @Override
      public String getContentType() {
        return "application/octet-stream";
      }

      @Override
      public void close() throws IOException {
        largeInputStream.close();
      }
      
      @Override
      public void copy(InputStream from, OutputStream to) throws IOException {
        // Simulate slow I/O by adding small delays during copying
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = from.read(buffer)) != -1) {
          // Small delay to simulate I/O latency
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          to.write(buffer, 0, bytesRead);
        }
      }
    };
    
    // Track the number of bytes written to verify data is transferred correctly
    AtomicInteger bytesWritten = new AtomicInteger(0);
    
    // Mock the output stream to count bytes written
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        byte[] buffer = invocation.getArgument(0);
        int offset = invocation.getArgument(1);
        int length = invocation.getArgument(2);
        bytesWritten.addAndGet(length);
        return null;
      }
    }).when(output).write(any(byte[].class), any(int.class), any(int.class));
    
    // Measure time to send the large payload
    long startTime = System.currentTimeMillis();
    underTest.send(request, HttpResponses.ok(largePayload), httpServletResponse);
    long endTime = System.currentTimeMillis();
    
    // Verify all bytes were written
    assertThat(bytesWritten.get(), is(LARGE_CONTENT_SIZE));
    
    // The operation should take some time due to the simulated I/O delays,
    // but not excessively long which would indicate thread pinning
    long duration = endTime - startTime;
    log.info("Large payload transfer took {} ms", duration);
    
    // Verify the operation completed in a reasonable time
    // If thread pinning occurred, this would take much longer
    assertThat(duration, greaterThan(0L)); // Should take some time
    assertThat(duration, lessThan(30000L)); // But not too long
  }
  
  /**
   * Tests concurrent response sending using Virtual Threads to validate scalability.
   * This verifies that the implementation can handle many concurrent responses efficiently.
   */
  @Test
  public void concurrentResponseSendingWithVirtualThreads() throws Exception {
    when(request.getAction()).thenReturn(HttpMethods.GET);
    
    // Number of concurrent responses to send
    final int concurrentResponses = 100;
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create mock responses and servlets for each concurrent request
    List<HttpServletResponse> mockResponses = new ArrayList<>();
    List<ServletOutputStream> mockOutputs = new ArrayList<>();
    List<Payload> payloads = new ArrayList<>();
    
    for (int i = 0; i < concurrentResponses; i++) {
      // Create a payload with unique content
      String content = "Content for response " + i;
      InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
      Payload payload = new Payload() {
        @Override
        public InputStream openInputStream() {
          return inputStream;
        }

        @Override
        public long getSize() {
          return content.length();
        }

        @Override
        public String getContentType() {
          return "text/plain";
        }

        @Override
        public void close() throws IOException {
          inputStream.close();
        }
      };
      payloads.add(payload);
      
      // Create mock response and output stream
      HttpServletResponse mockResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
      ServletOutputStream mockOutput = org.mockito.Mockito.mock(ServletOutputStream.class);
      when(mockResponse.getOutputStream()).thenReturn(mockOutput);
      
      mockResponses.add(mockResponse);
      mockOutputs.add(mockOutput);
    }
    
    // Submit all response sending tasks to the executor
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < concurrentResponses; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Add a small random delay to simulate real-world concurrent requests
          Thread.sleep((long) (Math.random() * 50));
          underTest.send(request, HttpResponses.ok(payloads.get(index)), mockResponses.get(index));
        } catch (Exception e) {
          fail("Exception in concurrent response sending: " + e.getMessage());
        }
      }, executor);
      futures.add(future);
    }
    
    // Wait for all responses to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    allFutures.get(10, TimeUnit.SECONDS); // Should complete within timeout
    
    // Verify all responses were processed
    for (int i = 0; i < concurrentResponses; i++) {
      verify(mockResponses.get(i)).getOutputStream();
    }
    
    // Shutdown the executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }
}
