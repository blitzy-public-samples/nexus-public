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
package org.sonatype.nexus.repository.cache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteConnectionStatus;
import org.sonatype.nexus.repository.httpclient.RemoteConnectionStatusType;
import org.sonatype.nexus.repository.replication.PullReplicationSupport;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class NegativeCacheHandlerTest
    extends TestSupport
{
  @Mock
  private NegativeCacheFacet mockNegativeCacheFacet;

  @Mock
  private NegativeCacheKey mockNegativeCacheKey;

  @Mock
  private Context mockContext;

  @Mock
  private Request mockRequest;

  @Mock
  private Repository mockRepository;

  @Mock
  private HttpClientFacet mockHttpFacet;

  @Mock
  private RemoteConnectionStatus mockConnectionStatus;

  private NegativeCacheHandler underTest;

  @BeforeEach
  void setUp() {
    underTest = new NegativeCacheHandler();

    when(mockContext.getRequest()).thenReturn(mockRequest);
    when(mockContext.getRepository()).thenReturn(mockRepository);
    when(mockRepository.facet(HttpClientFacet.class)).thenReturn(mockHttpFacet);
    when(mockHttpFacet.getStatus()).thenReturn(mockConnectionStatus);
    when(mockConnectionStatus.getType()).thenReturn(RemoteConnectionStatusType.AVAILABLE);
    when(mockRequest.getAction()).thenReturn(HttpMethods.GET);
    when(mockRepository.facet(NegativeCacheFacet.class)).thenReturn(mockNegativeCacheFacet);
    when(mockNegativeCacheFacet.getCacheKey(mockContext)).thenReturn(mockNegativeCacheKey);
  }

  /**
   * Given:
   * - request is not a GET/HEAD request
   * Then:
   *  - context is asked to proceed
   *  - response from context is passed on
   *  - no other actions (checked by no interactions with repository)
   */
  @Test
  void directlyProceedOnNonGetOrHeadRequests() throws Exception {
    when(mockRequest.getAction()).thenReturn(HttpMethods.PUT);
    Response contextResponse = HttpResponses.ok();
    when(mockContext.proceed()).thenReturn(contextResponse);
    Response response = underTest.handle(mockContext);
    assertSame(contextResponse, response);
    verify(mockContext).proceed();
    verify(mockRepository, never()).facet(any());
  }

  /**
   * Given:
   * - request is a Replication request
   * Then:
   *  - context is asked to proceed
   *  - response from context is passed on
   *  - if successful, cache is invalidated for key
   *  - no other actions (checked by no checking of key being cached)
   */
  @Test
  void directlyProceedOnReplicationRequestInvalidateOnSuccess() throws Exception {
    AttributesMap contextAttributes = new AttributesMap();
    contextAttributes.set(PullReplicationSupport.IS_REPLICATION_REQUEST, true);
    when(mockContext.getAttributes()).thenReturn(contextAttributes);
    Response contextResponse = HttpResponses.ok();
    when(mockContext.proceed()).thenReturn(contextResponse);
    Response response = underTest.handle(mockContext);
    assertSame(contextResponse, response);
    verify(mockContext).proceed();
    verify(mockNegativeCacheFacet).invalidate(mockNegativeCacheKey);
    verify(mockNegativeCacheFacet, never()).get(any());
  }
  /**
   * Given:
   * - request is a Replication request
   * Then:
   *  - context is asked to proceed
   *  - response from context is passed on
   *  - if not successful, cache is left as-is
   *  - no other actions (checked by no checking of key being cached)
   */
  @Test
  void directlyProceedOnReplicationRequestLeaveExistingOnFail() throws Exception {
    AttributesMap contextAttributes = new AttributesMap();
    contextAttributes.set(PullReplicationSupport.IS_REPLICATION_REQUEST, true);
    when(mockContext.getAttributes()).thenReturn(contextAttributes);
    Response contextResponse = HttpResponses.notFound();
    when(mockContext.proceed()).thenReturn(contextResponse);
    Response response = underTest.handle(mockContext);
    assertSame(contextResponse, response);
    verify(mockContext).proceed();
    verify(mockNegativeCacheFacet, never()).invalidate(mockNegativeCacheKey);
    verify(mockNegativeCacheFacet, never()).get(any());
  }

  /**
   * Given:
   * - no cached key present
   * - a 404 response from context for GET
   * Then:
   *  - 404 response is cached
   */
  @Test
  void a404ResponseGetsCachedForGet() throws Exception {
    when(mockRequest.getAction()).thenReturn(HttpMethods.GET);
    verify404Cached();
  }

  /**
   * Given:
   * - no cached key present
   * - a 404 response from context for GET
   * - context (remote) is auto blocked
   * Then:
   *  - 404 response is cached
   */
  @Test
  void a404ResponseSkipsCacheForAutoBlockedRemote() throws Exception {
    verifyCacheForBlockedRemote(RemoteConnectionStatusType.AUTO_BLOCKED_UNAVAILABLE);
  }

  /**
   * Given:
   * - no cached key present
   * - a 404 response from context for GET
   * - context (remote) is manually blocked
   * Then:
   *  - 404 response is cached
   */
  @Test
  void a404ResponseSkipsCacheForManualBlockedRemote() throws Exception {
    verifyCacheForBlockedRemote(RemoteConnectionStatusType.BLOCKED);
  }

  /**
   * Given:
   * - no cached key present
   * - a 404 response from context for HEAD
   * Then:
   *  - 404 response is cached
   */
  @Test
  void a404ResponseGetsCachedForHead() throws Exception {
    when(mockRequest.getAction()).thenReturn(HttpMethods.HEAD);
    verify404Cached();
  }

  /**
   * Given:
   * - cached key present
   * Then:
   *  - cached status is returned
   *  - context is not asked to proceed
   *  - key is not put in cache
   *  - key is not invalidated
   */
  @Test
  void returnCached404() throws Exception {
    Status cachedStatus = Status.failure(HttpStatus.NOT_FOUND, "404");
    when(mockNegativeCacheFacet.get(mockNegativeCacheKey)).thenReturn(cachedStatus);
    Response response = underTest.handle(mockContext);
    assertSame(cachedStatus, response.getStatus());
    verify(mockContext, never()).proceed();
    verify(mockNegativeCacheFacet, never()).put(any(NegativeCacheKey.class), any(Status.class));
    verify(mockNegativeCacheFacet, never()).invalidate(any(NegativeCacheKey.class));
  }

  /**
   * Given:
   * - no cached key present
   * - a non 404 response from context
   * Then:
   *  - context is asked to proceed
   *  - response from context is passed on
   *  - key is not put in cache
   *  - key is not invalidated
   */
  @Test
  void aNon404ResponsePassesThrough() throws Exception {
    Response contextResponse = HttpResponses.serviceUnavailable("503");
    when(mockContext.proceed()).thenReturn(contextResponse);
    when(mockNegativeCacheFacet.get(mockNegativeCacheKey)).thenReturn(null);
    Response response = underTest.handle(mockContext);
    assertSame(contextResponse, response);
    verify(mockContext).proceed();
    verify(mockNegativeCacheFacet, never()).put(any(NegativeCacheKey.class), any(Status.class));
    verify(mockNegativeCacheFacet, never()).invalidate(any(NegativeCacheKey.class));
  }

  /**
   * Given:
   * - no cached key present
   * - successful response from context
   * Then:
   *  - context is asked to proceed
   *  - response from context is passed on
   *  - key is not put in cache
   *  - key is invalidated
   */
  @Test
  void successfulResponseInvalidatesCache() throws Exception {
    Response contextResponse = HttpResponses.ok("200");
    when(mockContext.proceed()).thenReturn(contextResponse);
    when(mockNegativeCacheFacet.get(mockNegativeCacheKey)).thenReturn(null);
    Response response = underTest.handle(mockContext);
    assertSame(contextResponse, response);
    verify(mockContext).proceed();
    verify(mockNegativeCacheFacet, never()).put(any(NegativeCacheKey.class), any(Status.class));
    verify(mockNegativeCacheFacet).invalidate(any(NegativeCacheKey.class));
  }

  /**
   * Given:
   * - multiple concurrent requests using virtual threads
   * Then:
   *  - all requests are handled correctly without errors
   *  - thread safety of negative cache operations is verified
   */
  @Test
  void concurrentOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Set up common test conditions
      Response contextResponse = HttpResponses.notFound("404");
      when(mockContext.proceed()).thenReturn(contextResponse);
      when(mockNegativeCacheFacet.get(mockNegativeCacheKey)).thenReturn(null);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            Response response = underTest.handle(mockContext);
            if (response != contextResponse) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All virtual thread tasks should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent execution");
      
      // Verify the cache operations were called the expected number of times
      verify(mockNegativeCacheFacet, never()).invalidate(any(NegativeCacheKey.class));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verify 404 response is cached:
   * - context is asked to proceed
   * - response from context is passed on
   * - key is put in cache
   * - key is not invalidated
   */
  void verify404Cached() throws Exception {
    Response contextResponse = HttpResponses.notFound("404");
    when(mockContext.proceed()).thenReturn(contextResponse);
    when(mockNegativeCacheFacet.get(mockNegativeCacheKey)).thenReturn(null);
    Response response = underTest.handle(mockContext);
    assertSame(contextResponse, response);
    verify(mockContext).proceed();
    verify(mockNegativeCacheFacet).put(mockNegativeCacheKey, response.getStatus());
    verify(mockNegativeCacheFacet, never()).invalidate(any(NegativeCacheKey.class));
  }

  private void verifyCacheForBlockedRemote(final RemoteConnectionStatusType statusType) throws Exception {
    when(mockRequest.getAction()).thenReturn(HttpMethods.GET);
    when(mockConnectionStatus.getType()).thenReturn(statusType);
    Response contextResponse = HttpResponses.notFound("404");
    when(mockContext.proceed()).thenReturn(contextResponse);
    when(mockNegativeCacheFacet.get(mockNegativeCacheKey)).thenReturn(null);
    Response response = underTest.handle(mockContext);
    assertSame(contextResponse, response);
    verify(mockContext).proceed();
    verify(mockNegativeCacheFacet, never()).put(any(NegativeCacheKey.class), any(Status.class));
    verify(mockNegativeCacheFacet, never()).invalidate(mockNegativeCacheKey);
  }
}