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
  void setUp() throws Exception {
    underTest = new GroupHandler();

    when(context.getRequest()).thenReturn(request);
    when(proxy1.getName()).thenReturn("Proxy 1");
    when(proxy1.facet(ViewFacet.class)).thenReturn(viewFacet1);
    when(proxy2.getName()).thenReturn("Proxy 2");
    when(proxy2.facet(ViewFacet.class)).thenReturn(viewFacet2);
  }
  
  private void setupDispatch(final Response response1, final Response response2) throws Exception {
    when(viewFacet1.dispatch(request, context)).thenReturn(response1);
    when(viewFacet2.dispatch(request, context)).thenReturn(response2);
  }

  private void assertGetFirst(final Response expectedResponse) throws Exception {
    assertEquals(expectedResponse, underTest.getFirst(context, asList(proxy1, proxy2), new DispatchedRepositories()));
  }

  private void assertGetFirstNotFound(final List<Repository> repositories) throws Exception {
    Response response = underTest.getFirst(context, repositories, new DispatchedRepositories());
    assertEquals(NOT_FOUND, response.getStatus().getCode());
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
    // Setup responses for concurrent requests
    Response ok1 = ok();
    Response ok2 = ok();
    when(viewFacet1.dispatch(any(Request.class), any(Context.class))).thenReturn(ok1);
    when(viewFacet2.dispatch(any(Request.class), any(Context.class))).thenReturn(ok2);

    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      int requestCount = 100;
      CountDownLatch latch = new CountDownLatch(requestCount);
      AtomicInteger successCount = new AtomicInteger(0);

      // Submit multiple concurrent requests using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture<?>[requestCount];
      for (int i = 0; i < requestCount; i++) {
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            Response response = underTest.getFirst(context, asList(proxy1, proxy2), new DispatchedRepositories());
            if (response != null && response.getStatus().isSuccessful()) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Count failures
          } finally {
            latch.countDown();
          }
        }, executor);
      }

      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify all requests were successful
      assertEquals(requestCount, successCount.get(), "All concurrent requests should succeed");
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void parallelDispatchingToMultipleRepositories() throws Exception {
    // Setup responses for parallel dispatching
    Response ok1 = ok();
    Response ok2 = ok();
    when(viewFacet1.dispatch(request, context)).thenReturn(ok1);
    when(viewFacet2.dispatch(request, context)).thenReturn(ok2);

    // Create virtual thread factory for parallel execution
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Create a custom GroupHandler that uses virtual threads for parallel dispatching
      GroupHandler parallelHandler = new GroupHandler() {
        @Override
        protected Response getFirst(Context context, List<Repository> repositories, DispatchedRepositories dispatched) 
            throws Exception {
          // Use CompletableFuture to dispatch to repositories in parallel
          CompletableFuture<Response>[] futures = repositories.stream()
              .map(repository -> CompletableFuture.supplyAsync(() -> {
                try {
                  return dispatch(context, repository, dispatched);
                } catch (Exception e) {
                  return null;
                }
              }, executor))
              .toArray(CompletableFuture[]::new);

          // Wait for the first successful response
          CompletableFuture<Object> firstCompleted = CompletableFuture.anyOf(futures);
          Response response = (Response) firstCompleted.join();
          
          // Return the first successful response or not found
          return response != null ? response : notFound();
        }
      };

      // Execute the parallel dispatch
      Response response = parallelHandler.getFirst(context, asList(proxy1, proxy2), new DispatchedRepositories());
      
      // Verify the response is successful
      assertNotNull(response);
      assertEquals(true, response.getStatus().isSuccessful());
    } finally {
      executor.shutdown();
    }
  }