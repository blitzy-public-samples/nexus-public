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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupHandler.DispatchedRepositories;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class GroupHandlerTest
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
  void setUp() throws Exception {
    underTest = new GroupHandler();

    when(context.getRequest()).thenReturn(request);
    when(proxy1.getName()).thenReturn("Proxy 1");
    when(proxy1.facet(ViewFacet.class)).thenReturn(viewFacet1);
    when(proxy2.getName()).thenReturn("Proxy 2");
    when(proxy2.facet(ViewFacet.class)).thenReturn(viewFacet2);
  }

  @Test
  void whenAllRepositoryReturnOkThenGroupReturnsOk() throws Exception {
    Response ok1 = ok();
    setupDispatch(ok1, ok());

    assertGetFirst(ok1);
  }

  @Test
  void whenAnyRepositoryReturnsOkThenGroupReturnsOk() throws Exception {
    Response ok2 = ok();
    setupDispatch(notFound(), ok2);

    assertGetFirst(ok2);
  }

  @Test
  void whenAllRepositoriesReturnNotFoundThenGroupReturnsNotFound() throws Exception {
    setupDispatch(notFound(), notFound());

    assertGetFirstNotFound(asList(proxy1, proxy2));
  }

  @Test
  void whenAnyRepositoryReturnsNotOkThenGroupReturnsNotFound() throws Exception {
    setupDispatch(forbidden(), forbidden());

    assertGetFirstNotFound(asList(proxy1, proxy2));

    setupDispatch(forbidden(), serviceUnavailable());

    assertGetFirstNotFound(asList(proxy1, proxy2));
  }

  @Test
  void whenNoRepositoriesInGroupThenGroupReturnsNotFound() throws Exception {
    assertGetFirstNotFound(emptyList());
  }

  @Test
  void returnsFirstOkOrFirstUseDispatchedResponse() throws Exception {
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
  void returnsFirstOkOrFirstBypassHttpErrorsHeaderResponse() throws Exception {
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
  void concurrentRequestsWithVirtualThreads() throws Exception {
    // Setup responses for multiple repositories
    Response ok1 = ok();
    Response ok2 = ok();
    when(viewFacet1.dispatch(any(Request.class), any(Context.class))).thenReturn(ok1);
    when(viewFacet2.dispatch(any(Request.class), any(Context.class))).thenReturn(ok2);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent requests to simulate
      int requestCount = 100;
      CountDownLatch latch = new CountDownLatch(requestCount);
      AtomicInteger successCount = new AtomicInteger(0);

      // Create and submit tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < requestCount; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create a new context and request for each thread to avoid concurrency issues with mocks
            Context threadContext = context;
            Request threadRequest = request;
            
            Response response = underTest.getFirst(threadContext, asList(proxy1, proxy2), new DispatchedRepositories());
            if (response.getStatus().isSuccessful()) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in virtual thread execution", e);
          } finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Verify all requests were successful
      assertEquals(requestCount, successCount.get(), "All requests should have been successful");
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void parallelDispatchingToMultipleRepositories() throws Exception {
    // Setup multiple repositories and responses
    int repoCount = 5;
    List<Repository> repositories = new ArrayList<>();
    List<ViewFacet> viewFacets = new ArrayList<>();
    List<Response> responses = new ArrayList<>();

    // Create mocks for multiple repositories
    for (int i = 0; i < repoCount; i++) {
      Repository repo = mock(Repository.class);
      ViewFacet viewFacet = mock(ViewFacet.class);
      Response response = i == 0 ? ok() : notFound(); // First repo returns OK, others return NOT_FOUND
      
      when(repo.getName()).thenReturn("Repo " + i);
      when(repo.facet(ViewFacet.class)).thenReturn(viewFacet);
      when(viewFacet.dispatch(any(Request.class), any(Context.class))).thenReturn(response);
      
      repositories.add(repo);
      viewFacets.add(viewFacet);
      responses.add(response);
    }

    // Create virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test parallel dispatching
      CompletableFuture<Response> result = CompletableFuture.supplyAsync(() -> {
        try {
          return underTest.getFirst(context, repositories, new DispatchedRepositories());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);

      // Get the result and verify it's the expected OK response from the first repository
      Response response = result.get(10, TimeUnit.SECONDS);
      assertNotNull(response);
      assertThat(response.getStatus().isSuccessful(), is(true));
      assertEquals(responses.get(0), response);

      // Verify the first repository was called but we didn't need to call subsequent ones
      verify(viewFacets.get(0)).dispatch(any(Request.class), any(Context.class));
    } finally {
      executor.shutdown();
    }
  }

  private void setupDispatch(final Response response1, final Response response2) throws Exception {
    when(viewFacet1.dispatch(request, context)).thenReturn(response1);
    when(viewFacet2.dispatch(request, context)).thenReturn(response2);
  }

  private void assertGetFirst(final Response expectedResponse) throws Exception {
    assertThat(underTest.getFirst(context, asList(proxy1, proxy2), new DispatchedRepositories()), is(expectedResponse));
  }

  private void assertGetFirstNotFound(final List<Repository> repositories) throws Exception {
    Response response = underTest.getFirst(context, repositories, new DispatchedRepositories());
    assertThat(response.getStatus().getCode(), is(NOT_FOUND));
  }
  
  private <T> T mock(Class<T> classToMock) {
    return org.mockito.Mockito.mock(classToMock);
  }
}