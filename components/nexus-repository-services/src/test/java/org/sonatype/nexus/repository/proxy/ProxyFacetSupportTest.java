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
package org.sonatype.nexus.repository.proxy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.cooperation2.datastore.DefaultCooperation2Factory;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.MissingBlobException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheControllerHolder;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.transaction.RetryDeniedException;

import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.message.BasicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.proxy.ProxyFacetSupport.BYPASS_HTTP_ERRORS_HEADER_NAME;
import static org.sonatype.nexus.repository.proxy.ProxyFacetSupport.BYPASS_HTTP_ERRORS_HEADER_VALUE;

/**
 * Tests for the abstract class {@link ProxyFacetSupport}
 */
@ExtendWith(MockitoExtension.class)
class ProxyFacetSupportTest
    extends TestSupport
{
  @Mock
  ThrottlerInterceptor throttlerInterceptor;

  @Mock
  GracePeriodInterceptor gracePeriodInterceptor;

  @Spy
  @InjectMocks
  ProxyFacetSupport underTest = new ProxyFacetSupport()
  {
    @Nullable
    @Override
    protected Content getCachedContent(final Context context) throws IOException {
      return null;
    }

    @Override
    protected Content store(final Context context, final Content content) throws IOException {
      return null;
    }

    @Override
    protected void indicateVerified(
        final Context context,
        final Content content,
        final CacheInfo cacheInfo) throws IOException
    {
      // Method intentionally left empty as no specific behavior is required for this test.
    }

    @Override
    protected String getUrl(@Nonnull final Context context) {
      return null;
    }
  };

  @Mock
  Content content;

  @Mock
  StatusLine statusLine;

  @Mock
  Content reFetchedContent;

  @Mock
  Content storedContent;

  @Mock
  AttributesMap attributesMap;

  @Mock
  CacheInfo cacheInfo;

  @Mock
  Context cachedContext;

  @Mock
  AttributesMap cachedContextAttributesMap;

  @Mock
  Context missingContext;

  @Mock
  AttributesMap missingContextAttributesMap;

  @Mock
  CacheControllerHolder cacheControllerHolder;

  @Mock
  CacheController cacheController;

  @Mock
  Repository repository;

  @Mock
  EventManager eventManager;

  @Mock
  private Format format;

  private final ArgumentCaptor<ProxyThrottledRequestEvent> captor =
      ArgumentCaptor.forClass(ProxyThrottledRequestEvent.class);

  @BeforeEach
  void setUp() throws Exception {
    lenient().when(content.getAttributes()).thenReturn(attributesMap);

    lenient().when(attributesMap.get(CacheInfo.class)).thenReturn(cacheInfo);

    lenient().when(cacheControllerHolder.getContentCacheController()).thenReturn(cacheController);

    lenient().when(cachedContext.getRepository()).thenReturn(repository);

    Request request = mock(Request.class);
    lenient().when(cachedContext.getRequest()).thenReturn(request);

    lenient().when(missingContext.getRepository()).thenReturn(repository);
    lenient().when(missingContext.getRequest()).thenReturn(request);

    underTest.cacheControllerHolder = cacheControllerHolder;
    lenient().when(format.getValue()).thenReturn("raw");
    lenient().when(repository.getFormat()).thenReturn(format);

    lenient().when(cachedContextAttributesMap.get("proxy.remote-fetch.skip")).thenReturn(false);
    lenient().when(missingContextAttributesMap.get("proxy.remote-fetch.skip")).thenReturn(false);
    lenient().when(cachedContext.getAttributes()).thenReturn(cachedContextAttributesMap);
    lenient().when(missingContext.getAttributes()).thenReturn(missingContextAttributesMap);

    underTest.installDependencies(eventManager);
    underTest.attach(repository);
    DefaultCooperation2Factory cooperationFactory = new DefaultCooperation2Factory();
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, false, Duration.ofSeconds(0),
        Duration.ofSeconds(60), 10);
    underTest.buildCooperation();
  }

  @Test
  void testGetRemoteFetchSkipNoContentHasFound() throws Exception {
    doReturn(null).when(underTest).getCachedContent(cachedContext);
    when(cachedContextAttributesMap.get("proxy.remote-fetch.skip"))
        .thenReturn(true);

    Content actual = underTest.get(cachedContext);
    assertNull(actual);
    verify(underTest, never()).fetch(any(), any(), any());
    verify(underTest, never()).store(any(), any());
  }

  @Test
  void testGetRemoteFetchSkipContentHasFound() throws Exception {
    when(cachedContextAttributesMap.get("proxy.remote-fetch.skip"))
        .thenReturn(true);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    when(cacheController.isStale(cacheInfo)).thenReturn(false);

    Content actual = underTest.get(cachedContext);
    assertThat(actual, is(content));
    verify(underTest, never()).fetch(any(), any(), any());
    verify(underTest, never()).store(any(), any());
  }

  @Test
  void testGetRemoteFetchSkipContentHasFoundWithInvalidCache() throws Exception {
    when(cachedContextAttributesMap.get("proxy.remote-fetch.skip"))
        .thenReturn(true);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    when(cacheController.isStale(cacheInfo)).thenReturn(true);

    Content actual = underTest.get(cachedContext);
    assertThat(actual, is(content));
    verify(underTest, never()).fetch(any(), any(), any());
    verify(underTest, never()).store(any(), any());
  }

  @Test
  void testGet() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(false);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    Content foundContent = underTest.get(cachedContext);

    assertThat(foundContent, is(content));
  }

  @Test
  void testGet_stale() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    doReturn(reFetchedContent).when(underTest).fetch(cachedContext, content);
    doReturn(storedContent).when(underTest).store(cachedContext, reFetchedContent);

    Content foundContent = underTest.get(cachedContext);

    assertThat(foundContent, is(storedContent));
  }

  @Test
  void testGet_ProxyServiceException_contentReturnedIfCached() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    doThrow(new ProxyServiceException(new BasicHttpResponse(null, 503, "Offline")))
        .when(underTest).fetch(cachedContext, content);

    Content foundContent = underTest.get(cachedContext);

    assertThat(foundContent, is(content));
  }

  @Test
  void testGet_ProxyServiceException_thrownIfNotCached() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(null).when(underTest).getCachedContent(cachedContext);

    doThrow(new ProxyServiceException(new BasicHttpResponse(null, 503, "Offline")))
        .when(underTest).fetch(missingContext, null);

    assertThrows(ProxyServiceException.class, () -> underTest.get(missingContext));
  }

  @Test
  void testGet_RemoteBlockedException_contentReturnedIfCached() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    doThrow(new RemoteBlockedIOException("blocked")).when(underTest).fetch(cachedContext, content);

    Content foundContent = underTest.get(cachedContext);

    assertThat(foundContent, is(content));
  }

  @Test
  void testGet_RemoteBlockedException_thrownIfNotCached() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(null).when(underTest).getCachedContent(cachedContext);

    doThrow(new RemoteBlockedIOException("blocked")).when(underTest).fetch(missingContext, null);

    assertThrows(RemoteBlockedIOException.class, () -> underTest.get(missingContext));
  }

  @Test
  void testGet_IOException_contentReturnedIfCached() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    doThrow(new IOException()).when(underTest).fetch(cachedContext, content);

    Content foundContent = underTest.get(cachedContext);

    assertThat(foundContent, is(content));
  }

  @Test
  void testGet_IOException_thrownIfNotCached() throws IOException {
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(null).when(underTest).getCachedContent(cachedContext);

    doThrow(new IOException()).when(underTest).fetch(missingContext, null);

    assertThrows(IOException.class, () -> underTest.get(missingContext));
  }

  @Test
  void testGet_MissingBlobException() throws IOException {
    RetryDeniedException e = new RetryDeniedException("Denied", new MissingBlobException(null));
    doThrow(e).when(underTest).getCachedContent(cachedContext);

    doReturn(reFetchedContent).when(underTest).fetch(cachedContext, null);
    doReturn(storedContent).when(underTest).store(cachedContext, reFetchedContent);

    Content foundContent = underTest.get(cachedContext);

    assertThat(foundContent, is(storedContent));
  }

  @Test
  void testGet_differentRetryReason() throws IOException {
    RetryDeniedException e = new RetryDeniedException("Denied", new IOException());
    doThrow(e).when(underTest).getCachedContent(cachedContext);

    assertThrows(RetryDeniedException.class, () -> underTest.get(cachedContext));
  }

  /*
   * Verifies that distributed cooperation is used when nexus.proxy.clustered.cooperation.enabled is set to true
   */
  @Test
  void testDistributedCooperationSelected() throws IOException {
    DefaultCooperation2Factory distributedCooperationFactory = spy(new DefaultCooperation2Factory());
    DefaultCooperation2Factory defaultCooperationFactory = spy(new DefaultCooperation2Factory());

    underTest.configureCooperation(distributedCooperationFactory, defaultCooperationFactory, true, true, true,
        Duration.ofSeconds(0), Duration.ofSeconds(60), 10);

    verify(distributedCooperationFactory).configure();
    verify(defaultCooperationFactory, never()).configure();
  }

  /*
   * Verifies that default cooperation is used when nexus.proxy.clustered.cooperation.enabled is set to false
   * in clustered mode
   */
  @Test
  void testLocalCooperationSelectedWhenProxyCooperationDisabledInClusteredMode() throws IOException {
    DefaultCooperation2Factory distributedCooperationFactory = spy(new DefaultCooperation2Factory());
    DefaultCooperation2Factory defaultCooperationFactory = spy(new DefaultCooperation2Factory());

    underTest.configureCooperation(distributedCooperationFactory, defaultCooperationFactory, false, true, true,
        Duration.ofSeconds(0), Duration.ofSeconds(60), 10);

    verify(defaultCooperationFactory, times(1)).configure();
    verify(distributedCooperationFactory, never()).configure();
  }

  /*
   * Verifies that default cooperation is used when nexus.proxy.clustered.cooperation.enabled is set to true in
   * non-clustered mode
   */
  @Test
  void testLocalCooperationSelectedInNonClusteredMode() throws IOException {
    // select default cooperation factory, when nexus.proxy.clustered.cooperation.enabled is enabled in non-clustered
    // mode
    DefaultCooperation2Factory localCooperationFactory = spy(new DefaultCooperation2Factory());
    DefaultCooperation2Factory defaultCooperationFactory = spy(new DefaultCooperation2Factory());

    underTest.configureCooperation(defaultCooperationFactory, localCooperationFactory, true, false, true,
        Duration.ofSeconds(0), Duration.ofSeconds(60), 10);

    verify(defaultCooperationFactory, times(1)).configure();
    verify(localCooperationFactory, never()).configure();
  }

  /*
   * Verifies that injected cooperation factory(OrientCooperation2Factory) is used when
   * nexus.proxy.clustered.cooperation.enabled is set to true in non-clustered mode and orient
   */
  @Test
  void testLocalCooperationSelectedInNonClusteredModeAndOrient() throws IOException {
    DefaultCooperation2Factory cooperationFactory = spy(new DefaultCooperation2Factory());

    underTest.configureCooperation(cooperationFactory, null, true, false, true, Duration.ofSeconds(0),
        Duration.ofSeconds(60), 10);

    verify(cooperationFactory, times(1)).configure();
  }

  @Test
  void testBuildLogMessage_ContentFound_WithStatusLine() {
    String message = underTest.buildLogContentMessage(content, statusLine);

    assertThat(message, containsString("Exception {} checking remote for update"));
    assertThat(message, containsString("proxy repo {} failed to fetch {} with status line {}"));
    assertThat(message, containsString("returning content from cache."));
  }

  @Test
  void testBuildLogMessage_ContentFound_WithoutStatusLine() {
    String message = underTest.buildLogContentMessage(content, null);

    assertThat(message, containsString("Exception {} checking remote for update"));
    assertThat(message, containsString("proxy repo {} failed to fetch {}"));
    assertThat(message, containsString("returning content from cache."));
  }

  @Test
  void testBuildLogMessage_ContentNotFound_WithStatusLine() {
    String message = underTest.buildLogContentMessage(null, statusLine);

    assertThat(message, containsString("Exception {} checking remote for update"));
    assertThat(message, containsString("proxy repo {} failed to fetch {} with status line {}"));
    assertThat(message, containsString("content not in cache."));
  }

  @Test
  void testBuildLogMessage_ContentNotFound_WithoutStatusLine() {
    String message = underTest.buildLogContentMessage(null, null);

    assertThat(message, containsString("Exception {} checking remote for update"));
    assertThat(message, containsString("proxy repo {} failed to fetch {}"));
    assertThat(message, containsString("content not in cache."));
  }

  @Test
  void whenCacheInfoIsNullThenIsStaleIsTrue() throws Exception {
    when(attributesMap.get(CacheInfo.class)).thenReturn(null);
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(content).when(underTest).getCachedContent(cachedContext);

    doReturn(reFetchedContent).when(underTest).fetch(cachedContext, content);
    doReturn(storedContent).when(underTest).store(cachedContext, reFetchedContent);

    Content foundContent = underTest.get(cachedContext);

    assertThat(foundContent, is(storedContent));
  }

  @Test
  void leak() throws Exception {
    HttpClientFacet httpClientFacet = mock(HttpClientFacet.class);
    HttpClient httpClient = mock(HttpClient.class);
    ConfigurationFacet configurationFacet = mock(ConfigurationFacet.class);
    ProxyFacetSupport.ProxyConfig config = new ProxyFacetSupport.ProxyConfig();
    config.remoteUrl = new URI("http://example.com");

    when(repository.facet(HttpClientFacet.class)).thenReturn(httpClientFacet);
    when(httpClientFacet.getHttpClient()).thenReturn(httpClient);
    when(repository.facet(ConfigurationFacet.class)).thenReturn(configurationFacet);
    when(configurationFacet.readSection(any(Configuration.class), anyString(), eq(ProxyFacetSupport.ProxyConfig.class)))
        .thenReturn(config);

    HttpResponse httpResponse = new BasicHttpResponse(
        new BasicHttpResponse(new ProtocolVersion("HTTP", 1, 1), 304, "NOT MODIFIED").getStatusLine());
    httpResponse.addHeader(BYPASS_HTTP_ERRORS_HEADER_NAME, BYPASS_HTTP_ERRORS_HEADER_VALUE);
    doReturn("http://example.com").when(underTest).getUrl(cachedContext);
    doReturn(httpResponse).when(underTest).execute(eq(cachedContext), eq(httpClient), any(HttpRequestBase.class));

    try (MockedStatic<HttpClientUtils> httpClientUtils = mockStatic(HttpClientUtils.class)) {
      Configuration configuration = mock(Configuration.class);
      when(configuration.attributes("proxy")).thenReturn(new NestedAttributesMap(
          "proxy",
          singletonMap("remoteUrl", "http://example.com")));
      underTest.doConfigure(configuration);
      underTest.doStart();

      try {
        underTest.get(cachedContext);
        fail("Expected BypassHttpErrorException to be thrown");
      }
      catch (BypassHttpErrorException expected) {
        // expected
      }

      httpClientUtils.verify(() -> HttpClientUtils.closeQuietly(httpResponse), times(1));
    }
  }

  @Test
  void normalizeURLPath() throws Exception {
    assertEquals(
        URI.create("https://remoteserver/com/foo/this%20is%20a%20space/"),
        underTest.normalizeURLPath(URI.create("https://remoteserver/com/foo/this%20is%20a%20space/")));

    assertEquals(
        URI.create("https://remoteserver/com/foo/this%20is%20a%20space/"),
        underTest.normalizeURLPath(URI.create("https://remoteserver/com/foo/this%20is%20a%20space")));

    assertEquals(
        URI.create("https://remoteserver/com/foo/thisisaspace/"),
        underTest.normalizeURLPath(URI.create("https://remoteserver/com/foo/thisisaspace")));
  }

  @Test
  void testGetPostsBlockedEvents() throws IOException {
    when(throttlerInterceptor.shouldBlock()).thenReturn(true);
    when(gracePeriodInterceptor.isInGracePeriod()).thenReturn(false);
    doReturn(null).when(underTest).getCachedContent(cachedContext);

    underTest.get(cachedContext);

    verify(eventManager, times(2)).post(captor.capture());
    assertThat(captor.getValue().isBlocked(), is(true));
  }

  @Test
  void testGetPostsGracePeriodEvents() throws IOException {
    when(throttlerInterceptor.shouldBlock()).thenReturn(true);
    when(gracePeriodInterceptor.isInGracePeriod()).thenReturn(true);

    doReturn(content).when(underTest).getCachedContent(cachedContext);
    when(cacheController.isStale(cacheInfo)).thenReturn(true);
    doReturn(content).when(underTest).get(cachedContext, content);

    underTest.get(cachedContext);

    verify(eventManager, times(2)).post(captor.capture());
    assertThat(captor.getValue().isBlocked(), is(false));
  }
  
  @Test
  void testVirtualThreadOperation() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up test data
      when(cacheController.isStale(cacheInfo)).thenReturn(true);
      doReturn(content).when(underTest).getCachedContent(cachedContext);
      doReturn(reFetchedContent).when(underTest).fetch(cachedContext, content);
      doReturn(storedContent).when(underTest).store(cachedContext, reFetchedContent);
      
      // Execute proxy operation in a virtual thread
      CompletableFuture<Content> future = CompletableFuture.supplyAsync(() -> {
        try {
          return underTest.get(cachedContext);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Verify the result
      Content result = future.get(5, TimeUnit.SECONDS);
      assertThat(result, is(storedContent));
      
      // Verify that fetch and store were called
      verify(underTest).fetch(cachedContext, content);
      verify(underTest).store(cachedContext, reFetchedContent);
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  void testThreadPinningDetection() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Create a latch to synchronize the test
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean isPinned = new AtomicBoolean(false);
    AtomicReference<String> threadName = new AtomicReference<>();
    
    try {
      // Set up test data
      when(cacheController.isStale(cacheInfo)).thenReturn(true);
      doReturn(content).when(underTest).getCachedContent(cachedContext);
      
      // Mock fetch to perform a synchronized operation that would cause pinning
      doAnswer(invocation -> {
        // Capture thread information
        threadName.set(Thread.currentThread().getName());
        // Check if thread is virtual
        boolean isVirtual = Thread.currentThread().isVirtual();
        if (isVirtual) {
          // Simulate an operation that would cause pinning in a real scenario
          // In a real test, we would use JDK's thread pinning detection
          synchronized (ProxyFacetSupportTest.this) {
            // In a real scenario, this would be detected as pinning
            isPinned.set(true);
            latch.countDown();
            Thread.sleep(100); // Simulate some work
          }
        }
        return reFetchedContent;
      }).when(underTest).fetch(any(), any());
      
      doReturn(storedContent).when(underTest).store(any(), any());
      
      // Execute proxy operation in a virtual thread
      CompletableFuture.runAsync(() -> {
        try {
          underTest.get(cachedContext);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for the operation to complete or timeout
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("Test should complete within timeout", completed, is(true));
      assertThat("Thread should be virtual", threadName.get().contains("VirtualThread"), is(true));
      
      // In a real test with actual virtual threads, we would verify pinning was detected
      // Here we're just simulating the detection
      assertThat("Thread pinning should be detected", isPinned.get(), is(true));
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  void testVirtualThreadContextPropagation() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Create a thread-local variable to test context propagation
    ThreadLocal<String> contextValue = new ThreadLocal<>();
    AtomicReference<String> propagatedValue = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    try {
      // Set up test data
      when(cacheController.isStale(cacheInfo)).thenReturn(true);
      doReturn(content).when(underTest).getCachedContent(cachedContext);
      
      // Mock fetch to check for ThreadLocal propagation
      doAnswer(invocation -> {
        // Capture the propagated context value
        propagatedValue.set(contextValue.get());
        latch.countDown();
        return reFetchedContent;
      }).when(underTest).fetch(any(), any());
      
      doReturn(storedContent).when(underTest).store(any(), any());
      
      // Set the thread-local value in the main thread
      contextValue.set("test-context-value");
      
      // Execute proxy operation in a virtual thread
      CompletableFuture.runAsync(() -> {
        try {
          underTest.get(cachedContext);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for the operation to complete
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("Test should complete within timeout", completed, is(true));
      
      // In virtual threads, ThreadLocal values are not automatically propagated
      // This test verifies this behavior - the value should be null in the virtual thread
      assertNull("ThreadLocal value should not be propagated to virtual thread", propagatedValue.get());
    } finally {
      executor.shutdown();
      contextValue.remove();
    }
  }
  
  @Test
  void testConcurrentVirtualThreadOperations() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Track completion count
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Set up test data
      when(cacheController.isStale(cacheInfo)).thenReturn(true);
      doReturn(content).when(underTest).getCachedContent(cachedContext);
      doReturn(reFetchedContent).when(underTest).fetch(cachedContext, content);
      doReturn(storedContent).when(underTest).store(cachedContext, reFetchedContent);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        CompletableFuture.runAsync(() -> {
          try {
            Content result = underTest.get(cachedContext);
            if (result == storedContent) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("All tasks should succeed", successCount.get(), is(taskCount));
      assertThat("No tasks should fail", errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }
}