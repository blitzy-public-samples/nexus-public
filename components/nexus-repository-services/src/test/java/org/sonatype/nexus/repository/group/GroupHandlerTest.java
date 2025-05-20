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
package org.sonatype.nexus.repository.group;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupHandler.DispatchedRepositories;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;
import org.sonatype.nexus.repository.view.ViewFacet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.group.GroupHandler.USE_DISPATCHED_RESPONSE;
import static org.sonatype.nexus.repository.http.HttpResponses.forbidden;
import static org.sonatype.nexus.repository.http.HttpResponses.notFound;
import static org.sonatype.nexus.repository.http.HttpResponses.ok;
import static org.sonatype.nexus.repository.http.HttpResponses.serviceUnavailable;
import static org.sonatype.nexus.repository.http.HttpStatus.NOT_FOUND;
import static org.sonatype.nexus.repository.proxy.ProxyFacetSupport.BYPASS_HTTP_ERRORS_HEADER_NAME;
import static org.sonatype.nexus.repository.proxy.ProxyFacetSupport.BYPASS_HTTP_ERRORS_HEADER_VALUE;

@ExtendWith(MockitoExtension.class)
@Tag("Java21")
public class GroupHandlerTest
    extends TestSupport
{
  @Mock
  private Context context;

  @Mock
  private Request request;

  @Mock
  private Repository proxy1;

  @Mock
  private Repository proxy2;

  @Mock
  private ViewFacet viewFacet1;

  @Mock
  private ViewFacet viewFacet2;

  private GroupHandler underTest;

  @BeforeEach
  public void setUp() throws Exception {
    underTest = new GroupHandler();

    when(context.getRequest()).thenReturn(request);
    when(proxy1.getName()).thenReturn("Proxy 1");
    when(proxy1.facet(ViewFacet.class)).thenReturn(viewFacet1);
    when(proxy2.getName()).thenReturn("Proxy 2");
    when(proxy2.facet(ViewFacet.class)).thenReturn(viewFacet2);
  }

  @Test
  public void should_ReturnOk_WhenAllRepositoriesReturnOk() throws Exception {
    Response ok1 = ok();
    setupDispatch(ok1, ok());

    assertGetFirst(ok1);
  }

  @Test
  public void should_ReturnOk_WhenAnyRepositoryReturnsOk() throws Exception {
    Response ok2 = ok();
    setupDispatch(notFound(), ok2);

    assertGetFirst(ok2);
  }

  @Test
  public void should_ReturnNotFound_WhenAllRepositoriesReturnNotFound() throws Exception {
    setupDispatch(notFound(), notFound());

    assertGetFirstNotFound(asList(proxy1, proxy2));
  }

  @Test
  public void should_ReturnNotFound_WhenAnyRepositoryReturnsNotOk() throws Exception {
    setupDispatch(forbidden(), forbidden());

    assertGetFirstNotFound(asList(proxy1, proxy2));

    setupDispatch(forbidden(), serviceUnavailable());

    assertGetFirstNotFound(asList(proxy1, proxy2));
  }

  @Test
  public void should_ReturnNotFound_WhenNoRepositoriesInGroup() throws Exception {
    assertGetFirstNotFound(emptyList());
  }

  @Test
  public void should_ReturnFirstOkOrFirstUseDispatchedResponse() throws Exception {
    Response ok = ok();
    Response forbidden1 = forbidden();

    forbidden1.getAttributes().set(USE_DISPATCHED_RESPONSE, true);
    setupDispatch(ok, forbidden1);

    assertGetFirst(ok);
    verify(viewFacet1).dispatch(request, context);
    verify(viewFacet2, times(0)).dispatch(request, context);

    setupDispatch(forbidden1, ok);

    assertGetFirst(forbidden1);
    verify(viewFacet1, times(2)).dispatch(request, context);
    verify(viewFacet2, times(0)).dispatch(request, context);
  }

  @Test
  public void should_ReturnFirstOkOrFirstBypassHttpErrorsHeaderResponse() throws Exception {
    Response forbidden = forbidden();
    forbidden.getHeaders().set(BYPASS_HTTP_ERRORS_HEADER_NAME, BYPASS_HTTP_ERRORS_HEADER_VALUE);

    Response ok = ok();

    setupDispatch(forbidden, ok);
    assertGetFirst(forbidden);
    verify(viewFacet1, times(1)).dispatch(request, context);
    verify(viewFacet2, times(0)).dispatch(request, context);

    setupDispatch(ok, forbidden);
    assertGetFirst(ok);
    verify(viewFacet1, times(2)).dispatch(request, context);
    verify(viewFacet2, times(0)).dispatch(request, context);
  }

  @Test
  public void should_ValidateResponse_UsingPatternMatching() {
    // Test successful response
    Response okResponse = ok();
    assertTrue(underTest.isValidResponse(okResponse));

    // Test response with USE_DISPATCHED_RESPONSE attribute
    Response dispatchedResponse = notFound();
    dispatchedResponse.getAttributes().set(USE_DISPATCHED_RESPONSE, true);
    assertTrue(underTest.isValidResponse(dispatchedResponse));

    // Test response with bypass header
    Response bypassResponse = forbidden();
    bypassResponse.getHeaders().set(BYPASS_HTTP_ERRORS_HEADER_NAME, BYPASS_HTTP_ERRORS_HEADER_VALUE);
    assertTrue(underTest.isValidResponse(bypassResponse));
  }

  @Test
  public void should_HandleConcurrentRequests_WithVirtualThreads() throws Exception {
    // Setup responses
    Response ok1 = ok();
    Response ok2 = ok();
    
    // Setup mocks to return responses
    when(viewFacet1.dispatch(any(), any())).thenReturn(ok1);
    when(viewFacet2.dispatch(any(), any())).thenReturn(ok2);
    
    // Create a latch to synchronize the test
    CountDownLatch latch = new CountDownLatch(2);
    
    // Create two virtual threads to make concurrent requests
    Thread vt1 = Thread.startVirtualThread(() -> {
      try {
        Response response = underTest.getFirst(context, asList(proxy1, proxy2), new DispatchedRepositories());
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatus().getCode());
        latch.countDown();
      }
      catch (Exception e) {
        log.error("Error in virtual thread 1", e);
      }
    });
    
    Thread vt2 = Thread.startVirtualThread(() -> {
      try {
        Response response = underTest.getFirst(context, asList(proxy2, proxy1), new DispatchedRepositories());
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatus().getCode());
        latch.countDown();
      }
      catch (Exception e) {
        log.error("Error in virtual thread 2", e);
      }
    });
    
    // Wait for both threads to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
  }

  @Test
  public void should_HandleConcurrentRepositoryAccess_WithVirtualThreads() throws Exception {
    // Setup a delayed response for the first repository
    when(viewFacet1.dispatch(any(), any())).thenAnswer(invocation -> {
      // Simulate a slow repository
      Thread.sleep(500);
      return ok();
    });
    
    // Setup an immediate response for the second repository
    when(viewFacet2.dispatch(any(), any())).thenReturn(ok());
    
    // Create a CompletableFuture to run the getFirst method asynchronously
    CompletableFuture<Response> future = CompletableFuture.supplyAsync(() -> {
      try {
        return underTest.getFirst(context, asList(proxy1, proxy2), new DispatchedRepositories());
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the result
    Response response = future.get(2, TimeUnit.SECONDS);
    
    // Verify we got a response
    assertNotNull(response);
    assertEquals(HttpStatus.OK, response.getStatus().getCode());
  }

  private void setupDispatch(final Response response1, final Response response2) throws Exception {
    when(viewFacet1.dispatch(request, context)).thenReturn(response1);
    when(viewFacet2.dispatch(request, context)).thenReturn(response2);
  }

  private void assertGetFirst(final Response expectedResponse) throws Exception {
    Response actualResponse = underTest.getFirst(context, asList(proxy1, proxy2), new DispatchedRepositories());
    assertEquals(expectedResponse, actualResponse);
  }

  private void assertGetFirstNotFound(final List<Repository> repositories) throws Exception {
    Response response = underTest.getFirst(context, repositories, new DispatchedRepositories());
    assertEquals(NOT_FOUND, response.getStatus().getCode());
  }
}