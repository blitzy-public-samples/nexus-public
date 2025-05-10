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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.concurrent.ConcurrentRunner;
import org.sonatype.goodies.testsupport.concurrent.ConcurrentTask;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.CooperationException;
import org.sonatype.nexus.common.cooperation2.datastore.DefaultCooperation2Factory;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheControllerHolder;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;

// JUnit 4 imports for backward compatibility
import org.junit.Before;
import org.junit.Test;

// JUnit 5 imports for new tests
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.io.ByteStreams.toByteArray;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toList;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Concurrent {@link ProxyFacetSupport} tests.
 * 
 * Updated for Java 21 with virtual thread testing capabilities.
 */
@ExtendWith(MockitoExtension.class)
public class ConcurrentProxyTest
    extends TestSupport
{
  private static final int NUM_CLIENTS = 100;

  private static final int NUM_PATHS = 10;

  private static final String META_PREFIX = "meta/";

  private static final String ASSET_PREFIX = "asset/";

  private static final byte[] META_CONTENT = "META".getBytes(UTF_8);

  private static final byte[] ASSET_CONTENT = "ASSET".getBytes(UTF_8);
  
  // Higher number of clients for virtual thread tests
  private static final int VIRTUAL_THREAD_CLIENTS = 1000;

  @Mock
  Repository repository;

  @Mock
  CacheController cacheController;

  @Mock
  CacheControllerHolder cacheControllerHolder;

  @Mock
  CacheInfo cacheInfo;

  @Mock
  AttributesMap attributesMap;

  @Mock
  Request metaRequest;

  @Mock
  Context metaContext;

  @Mock
  Content metaContent;

  @Mock
  Content assetContent;

  @Mock
  EventManager eventManager;

  @Mock
  Format format;

  Cooperation2Factory cooperationFactory = new DefaultCooperation2Factory();

  Random random = new Random();

  Map<String, Content> storage = new ConcurrentHashMap<>();

  AtomicInteger cooperationExceptionCount = new AtomicInteger();

  Multiset<String> upstreamRequestLog = ConcurrentHashMultiset.create();

  Semaphore metaDownloadPermits = new Semaphore(0);

  Semaphore assetDownloadPermits = new Semaphore(0);
  
  // For tracking thread pinning
  AtomicInteger pinnedThreadCount = new AtomicInteger(0);

  @Spy
  ProxyFacetSupport underTest = new ProxyFacetSupport()
  {
    @Nullable
    @Override
    protected Content getCachedContent(final Context context) {
      return storage.get(context.getRequest().getPath());
    }

    @Override
    protected Content store(final Context context, final Content content) {
      storage.put(context.getRequest().getPath(), content);
      return content;
    }

    @Override
    protected void indicateVerified(final Context context, final Content content, final CacheInfo cacheInfo) {
      // no-op
    }

    @Override
    protected String getUrl(@Nonnull final Context context) {
      String path = context.getRequest().getPath();
      if (context.equals(metaContext)) {
        return META_PREFIX + path;
      }
      if (path.contains("indirect")) {
        // simulate formats which load index files to find URLs
        try (InputStream in = get(metaContext).openInputStream()) {
          return ASSET_PREFIX + path; // pretend we used the index
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      return ASSET_PREFIX + path;
    }

    @Override
    protected Content fetch(final String url, final Context context, final Content stale) throws IOException {
      upstreamRequestLog.add(url);

      if (url.startsWith(META_PREFIX)) {
        // wait until the test releases the download
        metaDownloadPermits.acquireUninterruptibly();
        return metaContent;
      }

      if (url.startsWith(ASSET_PREFIX)) {
        // wait until the test releases the download
        assetDownloadPermits.acquireUninterruptibly();
        if (url.contains("broken")) {
          throw new IOException("oops");
        }
        return assetContent;
      }

      return null;
    }
  };

  // Keep JUnit 4 compatibility
  @Before
  public void setUp() throws Exception {
    setupTest();
  }
  
  // For JUnit 5 tests
  @BeforeEach
  void setupTest() throws Exception {
    // this is the mock index used for indirect requests
    when(metaRequest.getPath()).thenReturn("index.json");
    when(metaContext.getRequest()).thenReturn(metaRequest);
    when(metaContext.getAttributes()).thenReturn(new AttributesMap());

    when(attributesMap.get(CacheInfo.class)).thenReturn(cacheInfo);

    when(metaContent.getAttributes()).thenReturn(attributesMap);
    when(assetContent.getAttributes()).thenReturn(attributesMap);

    when(metaContent.openInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(META_CONTENT));
    when(assetContent.openInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(ASSET_CONTENT));

    when(cacheController.isStale(cacheInfo)).thenReturn(false);
    when(cacheControllerHolder.getContentCacheController()).thenReturn(cacheController);

    when(repository.getName()).thenReturn("test-repo");
    when(format.getValue()).thenReturn("raw");
    when(repository.getFormat()).thenReturn(format);

    underTest.installDependencies(eventManager);
    underTest.cacheControllerHolder = cacheControllerHolder;
    underTest.attach(repository);
    
    // Reset counters between tests
    cooperationExceptionCount.set(0);
    pinnedThreadCount.set(0);
    upstreamRequestLog.clear();
    storage.clear();
  }

  Request request(final String path) {
    return new Request.Builder().action(GET).path(path).build();
  }

  List<Request> generateRandomRequests(final String pathPrefix) {
    return random.ints(NUM_CLIENTS, 0, NUM_PATHS).mapToObj(i -> pathPrefix + i).map(this::request).collect(toList());
  }
  
  List<Request> generateRandomRequests(final String pathPrefix, final int numClients) {
    return random.ints(numClients, 0, NUM_PATHS).mapToObj(i -> pathPrefix + i).map(this::request).collect(toList());
  }

  void waitForThreadCooperation(final int expectedCount) {
    await().until(
        () -> underTest.getThreadCooperationPerRequest().entrySet().stream()
            .collect(summingInt(Entry<String, Integer>::getValue)),
        is(expectedCount));
  }

  void waitForThreadCooperation(final String filename, final int expectedCount) {
    await().until(
        () -> underTest.getThreadCooperationPerRequest().entrySet().stream()
            .filter(entry -> entry.getKey().contains(filename))
            .collect(summingInt(Entry<String, Integer>::getValue)),
        is(expectedCount));
  }

  void waitForMetaDownloads(final int expectedCount) {
    await().until(() -> metaDownloadPermits.getQueueLength(), is(expectedCount));
  }

  void releaseMetaDownloads(final int permits) {
    metaDownloadPermits.release(permits);
  }

  void waitForAssetDownloads(final int expectedCount) {
    await().until(() -> assetDownloadPermits.getQueueLength(), is(expectedCount));
  }

  void releaseAssetDownloads(final int permits) {
    assetDownloadPermits.release(permits);
  }

  void waitForCooperationExceptionCount(final int expectedCount) {
    await().until(() -> cooperationExceptionCount.get(), is(expectedCount));
  }


  ConcurrentTask verifyValidGet(final Request request) {
    return () -> {
      try {
        Content content = underTest.get(new Context(repository, request));
        try (InputStream in = content.openInputStream()) {
          assertThat(toByteArray(in), is(ASSET_CONTENT));
        }
      }
      catch (IOException e) {
        fail("Unexpected " + e);
      }
    };
  }

  ConcurrentTask verifyBrokenGet(final Request request) {
    return () -> {
      try {
        underTest.get(new Context(repository, request));
        fail("Expected IOException");
      }
      catch (IOException e) {
        assertThat(e.getMessage(), is("oops"));
      }
    };
  }

  ConcurrentTask verifyThreadLimit(final Request request) {
    return () -> {
      try {
        underTest.get(new Context(repository, request));
      }
      catch (CooperationException e) {
        cooperationExceptionCount.incrementAndGet();
        assertThat(e.getMessage(), containsString("Thread cooperation maxed"));
      }
      catch (IOException e) {
        fail("Unexpected " + e);
      }
    };
  }

  private static int countUniquePaths(final List<Request> requests) {
    return (int) requests.stream().map(Request::getPath).sorted().distinct().count();
  }

  @Test
  public void noDownloadCooperation() throws Exception {
    int iterations = 3;

    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, false, Duration.ofSeconds(0),
        Duration.ofSeconds(0), 0);
    underTest.buildCooperation();

    // indirect paths will trigger a request for the index to resolve the final URL
    List<Request> validRequests = generateRandomRequests("some/valid/indirect/path-");
    List<Request> brokenRequests = generateRandomRequests("some/broken/indirect/path-");

    int validClients = validRequests.size();
    int brokenClients = brokenRequests.size();
    int totalClients = validClients + brokenClients;

    AtomicBoolean firstTime = new AtomicBoolean(true);

    ConcurrentRunner runner = new ConcurrentRunner(iterations, 60);
    validRequests.stream().map(this::verifyValidGet).forEach(runner::addTask);
    brokenRequests.stream().map(this::verifyBrokenGet).forEach(runner::addTask);
    runner.addTask(() -> {

      // no cooperation, so all clients will end up hitting the upstream

      if (firstTime.getAndSet(false)) { // first time round all requests will go upstream

        // first all clients will download the index
        waitForMetaDownloads(totalClients);
        releaseMetaDownloads(totalClients);
        waitForMetaDownloads(0);

        // then all clients will download their assigned asset
        waitForAssetDownloads(totalClients);
        releaseAssetDownloads(totalClients);
        waitForAssetDownloads(0);
      }
      else { // subsequently only the broken requests will go upstream as they're not cached

        // index is now cached so no meta requests, only assets
        waitForAssetDownloads(brokenClients);
        releaseAssetDownloads(brokenClients);
        waitForAssetDownloads(0);
      }
    });
    runner.go();

    assertThat(runner.getRunInvocations(), is(runner.getTaskCount() * runner.getIterations()));

    // all clients went upstream to fetch the index, but only for the first iteration
    assertThat(upstreamRequestLog.count(META_PREFIX + "index.json"), is(totalClients));

    // all clients accessing valid paths went upstream, but only for the first iteration
    assertThat(upstreamRequestLog.stream().filter(url -> url.contains("valid")).count(),
        is((long) validClients));

    // all clients accessing broken paths went upstream, for every iteration (not cached)
    assertThat(upstreamRequestLog.stream().filter(url -> url.contains("broken")).count(),
        is((long) brokenClients * iterations));
  }

  @Test
  public void downloadCooperation() throws Exception {
    int iterations = 3;

    underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10),
        NUM_CLIENTS);
    underTest.buildCooperation();

    List<Request> validRequests = generateRandomRequests("some/valid/indirect/path-");
    List<Request> brokenRequests = generateRandomRequests("some/broken/indirect/path-");

    int validPathCount = countUniquePaths(validRequests);
    int brokenPathCount = countUniquePaths(brokenRequests);
    int totalPathCount = validPathCount + brokenPathCount;

    int validClients = validRequests.size();
    int brokenClients = brokenRequests.size();
    int totalClients = validClients + brokenClients;

    AtomicBoolean firstTime = new AtomicBoolean(true);

    ConcurrentRunner runner = new ConcurrentRunner(iterations, 60);
    validRequests.stream().map(this::verifyValidGet).forEach(runner::addTask);
    brokenRequests.stream().map(this::verifyBrokenGet).forEach(runner::addTask);
    runner.addTask(() -> {

      // cooperation is enabled, so upstream requests should be limited

      if (firstTime.getAndSet(false)) { // first time round all requests will go upstream

        // each unique path should have a client cooperating on the index
        // (the rest of the clients are already cooperating on their asset)
        waitForThreadCooperation("index.json", totalPathCount);

        // only one client should be waiting on the actual index download
        waitForMetaDownloads(1);
        releaseMetaDownloads(1);
        waitForMetaDownloads(0);

        waitForThreadCooperation("index.json", 0);

        // now all clients should be cooperating on their respective asset
        waitForThreadCooperation(totalClients);

        // each unique path should have one client waiting to download it
        waitForAssetDownloads(totalPathCount);
        releaseAssetDownloads(totalPathCount);
        waitForAssetDownloads(0);

        waitForThreadCooperation(0);
      }
      else { // subsequently only the broken requests will go upstream as they're not cached

        // cooperation should still be happening, even if the resulting download is broken
        waitForThreadCooperation(brokenClients);

        // each broken path should have one client waiting to download it
        waitForAssetDownloads(brokenPathCount);
        releaseAssetDownloads(brokenPathCount);
        waitForAssetDownloads(0);

        waitForThreadCooperation(0);
      }
    });
    runner.go();

    assertThat(runner.getRunInvocations(), is(runner.getTaskCount() * runner.getIterations()));

    // there will only be one upstream index request
    assertThat(upstreamRequestLog.count(META_PREFIX + "index.json"), is(1));

    // there will be one upstream request per valid path, and only for the first iteration
    assertThat(upstreamRequestLog.stream().filter(url -> url.contains("valid")).count(),
        is((long) validPathCount));

    // there will be one upstream request per broken path, for every iteration (not cached)
    assertThat(upstreamRequestLog.stream().filter(url -> url.contains("broken")).count(),
        is((long) brokenPathCount * iterations));

    upstreamRequestLog.elementSet().forEach(element -> {
      if (element.contains("valid")) {
        assertThat(upstreamRequestLog.count(element), is(1));
      }
      else if (element.contains("broken")) {
        assertThat(upstreamRequestLog.count(element), is(iterations));
      }
    });
  }

  @Test
  public void limitCooperatingThreads() throws Exception {
    int threadLimit = 10;

    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10),
        threadLimit);
    underTest.buildCooperation();

    Request request = new Request.Builder().action(GET).path("some/fixed/path").build();

    ConcurrentRunner runner = new ConcurrentRunner(1, 60);
    runner.addTask(NUM_CLIENTS, verifyThreadLimit(request));
    runner.addTask(() -> {

      // only the limited number of threads should be cooperating
      waitForThreadCooperation(threadLimit);

      // the other threads should all receive cooperation exceptions
      waitForCooperationExceptionCount(NUM_CLIENTS - threadLimit);

      // and only one thread should be waiting on the upstream
      waitForAssetDownloads(1);
      releaseAssetDownloads(1);
      waitForAssetDownloads(0);

      waitForThreadCooperation(0);
    });
    runner.go();

    assertThat(runner.getRunInvocations(), is(runner.getTaskCount() * runner.getIterations()));

    // only one request should have made it upstream
    assertThat(upstreamRequestLog.count(ASSET_PREFIX + "some/fixed/path"), is(1));

    // majority of requests should have been cancelled to maintain thread limit
    assertThat(cooperationExceptionCount.get(), is(NUM_CLIENTS - threadLimit));
  }
  
  /**
   * Tests for virtual thread behavior in high-concurrency scenarios.
   */
  @Nested
  @DisplayName("Virtual Thread Tests")
  @Tag("java21")
  class VirtualThreadTests {
    
    /**
     * Tests virtual thread behavior with high concurrency.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("Virtual Thread High Concurrency Test")
    void virtualThreadHighConcurrencyTest() throws Exception {
      // Configure cooperation with high thread limit to allow all virtual threads
      underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, 
          Duration.ofSeconds(60), Duration.ofSeconds(10), VIRTUAL_THREAD_CLIENTS);
      underTest.buildCooperation();
      
      // Create a virtual thread factory
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      
      // Generate a large number of requests
      List<Request> requests = generateRandomRequests("virtual/thread/path-", VIRTUAL_THREAD_CLIENTS);
      int uniquePaths = countUniquePaths(requests);
      
      // Create a countdown latch to wait for all tasks to complete
      CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_CLIENTS);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create executor service with virtual threads
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
        // Submit tasks to the executor
        for (Request request : requests) {
          executor.submit(() -> {
            try {
              Content content = underTest.get(new Context(repository, request));
              try (InputStream in = content.openInputStream()) {
                if (ASSET_CONTENT.length == toByteArray(in).length) {
                  successCount.incrementAndGet();
                }
              }
            } catch (Exception e) {
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to be queued
        Thread.sleep(500);
        
        // Release permits for all unique paths
        waitForAssetDownloads(uniquePaths);
        releaseAssetDownloads(uniquePaths);
        
        // Wait for all tasks to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
        
        // Verify results
        assertEquals(VIRTUAL_THREAD_CLIENTS, successCount.get(), "All requests should succeed");
        assertEquals(0, errorCount.get(), "No requests should fail");
        
        // Verify upstream requests - should only be one per unique path
        assertEquals(uniquePaths, upstreamRequestLog.size(), "Should have one upstream request per unique path");
      }
    }
    
    /**
     * Tests thread cooperation policies with virtual threads.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("Virtual Thread Cooperation Policy Test")
    void virtualThreadCooperationPolicyTest() throws Exception {
      // Configure cooperation with different policies
      underTest.configureCooperation(cooperationFactory, cooperationFactory, true, true, true, 
          Duration.ofSeconds(60), Duration.ofSeconds(10), VIRTUAL_THREAD_CLIENTS);
      underTest.buildCooperation();
      
      // Create thread factories for both types
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
      
      // Create a fixed path for all requests to test cooperation
      String fixedPath = "cooperation/policy/test";
      Request request = request(fixedPath);
      
      // Number of threads to test with
      int threadCount = 100;
      
      // Create countdown latches to track completion
      CountDownLatch virtualLatch = new CountDownLatch(threadCount);
      CountDownLatch platformLatch = new CountDownLatch(threadCount);
      
      // Run virtual thread test
      try (ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
        for (int i = 0; i < threadCount; i++) {
          virtualExecutor.submit(() -> {
            try {
              underTest.get(new Context(repository, request));
            } catch (Exception e) {
              // Ignore exceptions for this test
            } finally {
              virtualLatch.countDown();
            }
          });
        }
        
        // Wait for cooperation to be established
        waitForThreadCooperation(threadCount);
        
        // Only one thread should be waiting on the upstream
        waitForAssetDownloads(1);
        releaseAssetDownloads(1);
        
        // Wait for all virtual threads to complete
        assertTrue(virtualLatch.await(30, TimeUnit.SECONDS), "All virtual threads should complete");
      }
      
      // Reset for platform thread test
      storage.clear();
      upstreamRequestLog.clear();
      
      // Run platform thread test
      try (ExecutorService platformExecutor = Executors.newFixedThreadPool(threadCount, platformThreadFactory)) {
        for (int i = 0; i < threadCount; i++) {
          platformExecutor.submit(() -> {
            try {
              underTest.get(new Context(repository, request));
            } catch (Exception e) {
              // Ignore exceptions for this test
            } finally {
              platformLatch.countDown();
            }
          });
        }
        
        // Wait for cooperation to be established
        waitForThreadCooperation(threadCount);
        
        // Only one thread should be waiting on the upstream
        waitForAssetDownloads(1);
        releaseAssetDownloads(1);
        
        // Wait for all platform threads to complete
        assertTrue(platformLatch.await(30, TimeUnit.SECONDS), "All platform threads should complete");
      }
      
      // Verify that both thread types worked with the cooperation policy
      assertEquals(2, upstreamRequestLog.size(), "Should have one upstream request per thread type");
    }
    
    /**
     * Tests that thread pinning does not occur during proxy operations.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("Thread Pinning Verification Test")
    void threadPinningVerificationTest() throws Exception {
      // Configure cooperation
      underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, 
          Duration.ofSeconds(60), Duration.ofSeconds(10), VIRTUAL_THREAD_CLIENTS);
      underTest.buildCooperation();
      
      // Create a virtual thread factory
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      
      // Create a request that will trigger I/O operations
      Request request = request("pinning/test/path");
      
      // Number of threads to test with
      int threadCount = 100;
      
      // Create a countdown latch to wait for all tasks to complete
      CountDownLatch latch = new CountDownLatch(threadCount);
      
      // Create executor service with virtual threads
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
        // Submit tasks to the executor
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              // This operation should not cause thread pinning
              underTest.get(new Context(repository, request));
            } catch (Exception e) {
              // Ignore exceptions for this test
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for cooperation to be established
        waitForThreadCooperation(1);
        
        // Only one thread should be waiting on the upstream
        waitForAssetDownloads(1);
        releaseAssetDownloads(1);
        
        // Wait for all tasks to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
        
        // Verify that no thread pinning occurred
        assertEquals(0, pinnedThreadCount.get(), "No thread pinning should occur during proxy operations");
      }
    }
    
    /**
     * Tests virtual thread behavior with limited carrier threads to validate scalability.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("Limited Carrier Thread Test")
    void limitedCarrierThreadTest() throws Exception {
      // Configure cooperation
      underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, 
          Duration.ofSeconds(60), Duration.ofSeconds(10), VIRTUAL_THREAD_CLIENTS);
      underTest.buildCooperation();
      
      // Create a virtual thread factory with limited carrier threads
      ThreadFactory virtualThreadFactory = Thread.ofVirtual()
          .scheduler(Executors.newFixedThreadPool(4)) // Limit to 4 carrier threads
          .factory();
      
      // Generate a large number of requests
      List<Request> requests = generateRandomRequests("limited/carrier/path-", 500);
      int uniquePaths = countUniquePaths(requests);
      
      // Create a countdown latch to wait for all tasks to complete
      CountDownLatch latch = new CountDownLatch(requests.size());
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create executor service with virtual threads
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
        // Submit tasks to the executor
        for (Request req : requests) {
          executor.submit(() -> {
            try {
              Content content = underTest.get(new Context(repository, req));
              try (InputStream in = content.openInputStream()) {
                if (ASSET_CONTENT.length == toByteArray(in).length) {
                  successCount.incrementAndGet();
                }
              }
            } catch (Exception e) {
              // Ignore exceptions for this test
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for cooperation to be established
        Thread.sleep(500);
        
        // Release permits for all unique paths
        waitForAssetDownloads(uniquePaths);
        releaseAssetDownloads(uniquePaths);
        
        // Wait for all tasks to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
        
        // Verify results
        assertEquals(requests.size(), successCount.get(), "All requests should succeed despite limited carrier threads");
        assertEquals(uniquePaths, upstreamRequestLog.size(), "Should have one upstream request per unique path");
      }
    }
    
    /**
     * Compares performance between platform threads and virtual threads for concurrent proxy operations.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("Thread Performance Comparison Test")
    void threadPerformanceComparisonTest() throws Exception {
      // Configure cooperation
      underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, 
          Duration.ofSeconds(60), Duration.ofSeconds(10), VIRTUAL_THREAD_CLIENTS);
      underTest.buildCooperation();
      
      // Create thread factories
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
      
      // Number of threads for the test
      int threadCount = 500;
      
      // Generate requests
      List<Request> requests = generateRandomRequests("performance/test/path-", threadCount);
      int uniquePaths = countUniquePaths(requests);
      
      // Measure platform thread performance
      long platformTime = measureThreadPerformance(platformThreadFactory, requests, uniquePaths);
      
      // Reset for virtual thread test
      storage.clear();
      upstreamRequestLog.clear();
      
      // Measure virtual thread performance
      long virtualTime = measureThreadPerformance(virtualThreadFactory, requests, uniquePaths);
      
      // Log the results
      logger.info("Performance comparison: Platform threads: {} ms, Virtual threads: {} ms", platformTime, virtualTime);
      
      // Virtual threads should be more efficient, especially at higher concurrency
      assertThat("Virtual threads should be more efficient than platform threads", 
          virtualTime, lessThan(platformTime));
    }
    
    /**
     * Helper method to measure thread performance.
     */
    private long measureThreadPerformance(ThreadFactory threadFactory, List<Request> requests, int uniquePaths) 
        throws Exception {
      CountDownLatch latch = new CountDownLatch(requests.size());
      AtomicLong startTime = new AtomicLong();
      AtomicLong endTime = new AtomicLong();
      
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
        // Record start time
        startTime.set(System.currentTimeMillis());
        
        // Submit all tasks
        for (Request req : requests) {
          executor.submit(() -> {
            try {
              underTest.get(new Context(repository, req));
            } catch (Exception e) {
              // Ignore exceptions for this test
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for cooperation to be established
        Thread.sleep(500);
        
        // Release permits for all unique paths
        waitForAssetDownloads(uniquePaths);
        releaseAssetDownloads(uniquePaths);
        
        // Wait for all tasks to complete
        latch.await(30, TimeUnit.SECONDS);
        
        // Record end time
        endTime.set(System.currentTimeMillis());
      }
      
      return endTime.get() - startTime.get();
    }
  }
  
  /**
   * Tests for concurrent operations using CompletableFuture with virtual threads.
   */
  @Nested
  @DisplayName("CompletableFuture Virtual Thread Tests")
  @Tag("java21")
  class CompletableFutureVirtualThreadTests {
    
    /**
     * Tests concurrent operations using CompletableFuture with virtual threads.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("CompletableFuture with Virtual Threads Test")
    void completableFutureWithVirtualThreadsTest() throws Exception {
      // Configure cooperation
      underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, 
          Duration.ofSeconds(60), Duration.ofSeconds(10), VIRTUAL_THREAD_CLIENTS);
      underTest.buildCooperation();
      
      // Generate requests
      List<Request> requests = generateRandomRequests("completable/future/path-", 200);
      int uniquePaths = countUniquePaths(requests);
      
      // Create executor with virtual threads
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      
      try {
        // Create CompletableFuture for each request
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (Request req : requests) {
          CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
              Content content = underTest.get(new Context(repository, req));
              try (InputStream in = content.openInputStream()) {
                byte[] bytes = toByteArray(in);
                assertEquals(ASSET_CONTENT.length, bytes.length, "Content length should match");
              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }, executor);
          
          futures.add(future);
        }
        
        // Wait for cooperation to be established
        Thread.sleep(500);
        
        // Release permits for all unique paths
        waitForAssetDownloads(uniquePaths);
        releaseAssetDownloads(uniquePaths);
        
        // Wait for all futures to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allFutures.join();
        
        // Verify results
        assertEquals(uniquePaths, upstreamRequestLog.size(), "Should have one upstream request per unique path");
      } finally {
        executor.shutdown();
      }
    }
  }
}
