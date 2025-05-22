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

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.repository.BadRequestException;
import org.sonatype.nexus.repository.httpbridge.HttpResponseSender;
import org.sonatype.nexus.repository.httpbridge.internal.describe.Description;
import org.sonatype.nexus.repository.httpbridge.internal.describe.DescriptionHelper;
import org.sonatype.nexus.repository.httpbridge.internal.describe.DescriptionRenderer;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.view.ContentTypes;
import org.sonatype.nexus.repository.view.Parameters;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;

import com.google.common.net.HttpHeaders;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.Mockito;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for describe functionality of {@link ViewServlet}.
 */
public class ViewServletTest
    extends TestSupport
{
  @Mock
  private Request request;

  @Mock
  private ViewFacet facet;

  @Mock(answer = RETURNS_DEEP_STUBS)
  private HttpServletResponse servletResponse;

  @Mock
  private DescriptionRenderer descriptionRenderer;

  @Mock(name = "facet-response", answer = RETURNS_DEEP_STUBS)
  private Response facetResponse;

  @Mock(name = "facet-exception", answer = RETURNS_DEEP_STUBS)
  private RuntimeException facetException;

  @Mock
  private HttpServletRequest httpServletRequest;

  private Parameters parameters;

  private DefaultHttpResponseSender defaultResponseSender;

  private ViewServlet underTest;

  @Before
  public void setUp() throws Exception {
    defaultResponseSender = spy(new DefaultHttpResponseSender());

    when(descriptionRenderer.renderHtml(any(Description.class))).thenReturn("HTML");
    when(descriptionRenderer.renderJson(any(Description.class))).thenReturn("JSON");

    underTest = spy(new ViewServlet(mock(RepositoryManager.class),
        new HttpResponseSenderSelector(Collections.<String, HttpResponseSender>emptyMap(), defaultResponseSender),
        mock(DescriptionHelper.class),
        descriptionRenderer, true
    ));

    when(request.getPath()).thenReturn("/test");

    parameters = new Parameters();
    when(request.getParameters()).thenReturn(parameters);

    BaseUrlHolder.set("http://placebo", "");
  }

  private void descriptionRequested(final String describe) {
    if (describe == null) {
      parameters.remove(ViewServlet.P_DESCRIBE);
    }
    else {
      parameters.set(ViewServlet.P_DESCRIBE, describe);
    }
  }

  @Test
  @Ignore("disabled, due to unknown heap issue with defaultResponseSender spy")
  public void normalRequestReturnsFacetResponse() throws Exception {
    descriptionRequested(null);
    facetThrowsException(false);

    underTest.dispatchAndSend(request, facet, defaultResponseSender, servletResponse);

    verify(underTest, never()).describe(
        any(Request.class),
        any(Response.class),
        any(Exception.class),
        any(String.class)
    );
    verify(defaultResponseSender).send(eq(request), any(Response.class), eq(servletResponse));
  }

  @Test
  public void describeRequestReturnsDescriptionResponse_HTML() throws Exception {
    descriptionRequested("HTML");
    facetThrowsException(false);

    underTest.dispatchAndSend(request, facet, defaultResponseSender, servletResponse);

    verify(underTest).describe(request, facetResponse, null, "HTML");
    verify(underTest).send(eq(request), any(Response.class), eq(servletResponse));
    verify(servletResponse).setContentType(ContentTypes.TEXT_HTML);
  }

  @Test
  public void describeRequestReturnsDescriptionResponse_JSON() throws Exception {
    descriptionRequested("JSON");
    facetThrowsException(false);

    underTest.dispatchAndSend(request, facet, defaultResponseSender, servletResponse);

    verify(underTest).describe(request, facetResponse, null, "JSON");
    verify(underTest).send(eq(request), any(Response.class), eq(servletResponse));
    verify(servletResponse).setContentType(ContentTypes.APPLICATION_JSON);
  }

  @Test(expected = RuntimeException.class)
  public void facetExceptionsReturnedNormally() throws Exception {
    descriptionRequested(null);
    facetThrowsException(true);

    underTest.dispatchAndSend(request, facet, defaultResponseSender, servletResponse);
  }

  @Test
  public void facetExceptionsAreDescribed() throws Exception {
    descriptionRequested("HTML");
    facetThrowsException(true);

    underTest.dispatchAndSend(request, facet, defaultResponseSender, servletResponse);

    // The exception got described
    verify(underTest).describe(request, null, facetException, "HTML");
    verify(underTest).send(eq(request), any(Response.class), eq(servletResponse));
  }

  @Test
  public void return400BadRequestOnBadRequestException() throws Exception {
    String message = "message";
    when(httpServletRequest.getPathInfo()).thenThrow(new BadRequestException(message));
    underTest.service(httpServletRequest, servletResponse);
    verify(servletResponse).setStatus(SC_BAD_REQUEST, message);
  }

  @Test
  public void responseHasContentSecurityPolicy() throws Exception {
    underTest.service(httpServletRequest, servletResponse);

    verify(servletResponse).setHeader(HttpHeaders.CONTENT_SECURITY_POLICY,
        "sandbox allow-forms allow-modals allow-popups allow-presentation allow-scripts allow-top-navigation");
  }

  @Test
  public void responseHasXssProtection() throws Exception {
    underTest.service(httpServletRequest, servletResponse);

    verify(servletResponse).setHeader(HttpHeaders.X_XSS_PROTECTION, "1; mode=block");
  }

  private void facetThrowsException(final boolean facetThrowsException) throws Exception {
    if (facetThrowsException) {
      when(facet.dispatch(request)).thenThrow(facetException);
    }
    else {
      when(facet.dispatch(request)).thenReturn(facetResponse);
    }
  }
  
  /**
   * Creates a new request mock with the given path.
   */
  private Request createRequestMock(String path) {
    Request req = mock(Request.class);
    when(req.getPath()).thenReturn(path);
    Parameters params = new Parameters();
    when(req.getParameters()).thenReturn(params);
    return req;
  }
  
  /**
   * Test to verify servlet behavior under high concurrency load using Virtual Threads.
   * This test simulates multiple concurrent requests to the servlet and verifies that
   * all requests are processed correctly without errors.
   */
  @Test
  public void concurrentRequestsWithVirtualThreads() throws Exception {
    // Number of concurrent requests to simulate
    final int concurrentRequests = 1000;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(concurrentRequests);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicReference<Exception> firstException = new AtomicReference<>();
    
    // Create a list to hold all the mock requests and responses
    List<Request> requests = new ArrayList<>();
    List<HttpServletResponse> responses = new ArrayList<>();
    List<ViewFacet> facets = new ArrayList<>();
    
    // Setup mocks for each request
    for (int i = 0; i < concurrentRequests; i++) {
      Request req = createRequestMock("/test/" + i);
      HttpServletResponse resp = mock(HttpServletResponse.class, RETURNS_DEEP_STUBS);
      ViewFacet fct = mock(ViewFacet.class);
      Response facetResp = mock(Response.class, RETURNS_DEEP_STUBS);
      
      when(fct.dispatch(req)).thenReturn(facetResp);
      
      requests.add(req);
      responses.add(resp);
      facets.add(fct);
    }
    
    // Use Java 21 Virtual Threads for concurrent execution
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks for concurrent execution
      for (int i = 0; i < concurrentRequests; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready before starting
            startLatch.await();
            
            // Process the request
            underTest.dispatchAndSend(requests.get(index), facets.get(index), 
                defaultResponseSender, responses.get(index));
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Store the first exception encountered
            firstException.compareAndSet(null, e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all requests to complete (with timeout)
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // Verify all requests completed successfully
      assertTrue("Not all requests completed within the timeout", completed);
      assertEquals("Not all requests succeeded", concurrentRequests, successCount.get());
      assertNull("Exceptions occurred during concurrent processing: " + 
          (firstException.get() != null ? firstException.get().getMessage() : ""), 
          firstException.get());
      
      // Verify that the response sender was called for each request
      verify(defaultResponseSender, times(concurrentRequests)).send(any(Request.class), 
          any(Response.class), any(HttpServletResponse.class));
    }
  }
  
  /**
   * Test to verify that security headers are properly applied in concurrent scenarios.
   * This test ensures that all responses have the required security headers set,
   * even under high concurrency with Virtual Threads.
   */
  @Test
  public void securityHeadersAppliedInConcurrentScenarios() throws Exception {
    // Number of concurrent requests to simulate
    final int concurrentRequests = 100;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(concurrentRequests);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicReference<Exception> firstException = new AtomicReference<>();
    
    // Create a list to hold all the mock requests
    List<HttpServletRequest> requests = new ArrayList<>();
    List<HttpServletResponse> responses = new ArrayList<>();
    
    // Setup mocks for each request
    for (int i = 0; i < concurrentRequests; i++) {
      HttpServletRequest req = mock(HttpServletRequest.class);
      HttpServletResponse resp = mock(HttpServletResponse.class);
      
      requests.add(req);
      responses.add(resp);
    }
    
    // Use Java 21 Virtual Threads for concurrent execution
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks for concurrent execution
      for (int i = 0; i < concurrentRequests; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready before starting
            startLatch.await();
            
            // Process the request
            underTest.service(requests.get(index), responses.get(index));
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Store the first exception encountered
            firstException.compareAndSet(null, e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all requests to complete (with timeout)
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // Verify all requests completed successfully
      assertTrue("Not all requests completed within the timeout", completed);
      assertEquals("Not all requests succeeded", concurrentRequests, successCount.get());
      assertNull("Exceptions occurred during concurrent processing: " + 
          (firstException.get() != null ? firstException.get().getMessage() : ""), 
          firstException.get());
      
      // Verify that security headers were set for each response
      for (HttpServletResponse response : responses) {
        verify(response).setHeader(HttpHeaders.CONTENT_SECURITY_POLICY,
            "sandbox allow-forms allow-modals allow-popups allow-presentation allow-scripts allow-top-navigation");
        verify(response).setHeader(HttpHeaders.X_XSS_PROTECTION, "1; mode=block");
      }
    }
  }
  
  /**
   * Test to verify servlet behavior when handling mixed request types concurrently.
   * This test simulates a mix of normal requests, describe requests, and error-generating
   * requests all being processed concurrently using Virtual Threads.
   */
  @Test
  public void mixedRequestTypesConcurrentHandling() throws Exception {
    // Number of each type of request
    final int normalRequests = 100;
    final int describeHtmlRequests = 50;
    final int describeJsonRequests = 50;
    final int errorRequests = 50;
    final int totalRequests = normalRequests + describeHtmlRequests + describeJsonRequests + errorRequests;
    
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(totalRequests);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicReference<Exception> firstException = new AtomicReference<>();
    
    // Create lists to hold all the mock objects
    List<Request> requests = new ArrayList<>();
    List<HttpServletResponse> responses = new ArrayList<>();
    List<ViewFacet> facets = new ArrayList<>();
    
    // Setup normal requests
    for (int i = 0; i < normalRequests; i++) {
      Request req = createRequestMock("/normal/" + i);
      HttpServletResponse resp = mock(HttpServletResponse.class, RETURNS_DEEP_STUBS);
      ViewFacet fct = mock(ViewFacet.class);
      Response facetResp = mock(Response.class, RETURNS_DEEP_STUBS);
      
      when(fct.dispatch(req)).thenReturn(facetResp);
      
      requests.add(req);
      responses.add(resp);
      facets.add(fct);
    }
    
    // Setup describe HTML requests
    for (int i = 0; i < describeHtmlRequests; i++) {
      Request req = createRequestMock("/describe-html/" + i);
      req.getParameters().set(ViewServlet.P_DESCRIBE, "HTML");
      HttpServletResponse resp = mock(HttpServletResponse.class, RETURNS_DEEP_STUBS);
      ViewFacet fct = mock(ViewFacet.class);
      Response facetResp = mock(Response.class, RETURNS_DEEP_STUBS);
      
      when(fct.dispatch(req)).thenReturn(facetResp);
      
      requests.add(req);
      responses.add(resp);
      facets.add(fct);
    }
    
    // Setup describe JSON requests
    for (int i = 0; i < describeJsonRequests; i++) {
      Request req = createRequestMock("/describe-json/" + i);
      req.getParameters().set(ViewServlet.P_DESCRIBE, "JSON");
      HttpServletResponse resp = mock(HttpServletResponse.class, RETURNS_DEEP_STUBS);
      ViewFacet fct = mock(ViewFacet.class);
      Response facetResp = mock(Response.class, RETURNS_DEEP_STUBS);
      
      when(fct.dispatch(req)).thenReturn(facetResp);
      
      requests.add(req);
      responses.add(resp);
      facets.add(fct);
    }
    
    // Setup error requests
    for (int i = 0; i < errorRequests; i++) {
      Request req = createRequestMock("/error/" + i);
      HttpServletResponse resp = mock(HttpServletResponse.class, RETURNS_DEEP_STUBS);
      ViewFacet fct = mock(ViewFacet.class);
      RuntimeException exception = mock(RuntimeException.class, RETURNS_DEEP_STUBS);
      
      when(fct.dispatch(req)).thenThrow(exception);
      
      requests.add(req);
      responses.add(resp);
      facets.add(fct);
    }
    
    // Use Java 21 Virtual Threads for concurrent execution
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks for concurrent execution
      for (int i = 0; i < totalRequests; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready before starting
            startLatch.await();
            
            // For error requests, we expect exceptions to be thrown
            if (index >= (normalRequests + describeHtmlRequests + describeJsonRequests)) {
              try {
                underTest.dispatchAndSend(requests.get(index), facets.get(index), 
                    defaultResponseSender, responses.get(index));
                // If we get here for error requests, it's unexpected
                fail("Expected exception was not thrown for error request " + index);
              } catch (RuntimeException e) {
                // This is expected for error requests
                successCount.incrementAndGet();
              }
            } else {
              // Process normal and describe requests
              underTest.dispatchAndSend(requests.get(index), facets.get(index), 
                  defaultResponseSender, responses.get(index));
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Only store unexpected exceptions
            if (index < (normalRequests + describeHtmlRequests + describeJsonRequests)) {
              firstException.compareAndSet(null, e);
            }
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all requests to complete (with timeout)
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // Verify all requests completed successfully
      assertTrue("Not all requests completed within the timeout", completed);
      assertEquals("Not all requests were processed correctly", totalRequests, successCount.get());
      assertNull("Unexpected exceptions occurred during concurrent processing: " + 
          (firstException.get() != null ? firstException.get().getMessage() : ""), 
          firstException.get());
      
      // Verify that the describe method was called for HTML and JSON describe requests
      verify(underTest, times(describeHtmlRequests)).describe(any(Request.class), any(Response.class), 
          Mockito.isNull(), eq("HTML"));
      verify(underTest, times(describeJsonRequests)).describe(any(Request.class), any(Response.class), 
          Mockito.isNull(), eq("JSON"));
      
      // Verify that the send method was called for all requests except error requests
      verify(underTest, times(normalRequests + describeHtmlRequests + describeJsonRequests))
          .send(any(Request.class), any(Response.class), any(HttpServletResponse.class));
    }
  }
}