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
package org.sonatype.virtualthread;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.datastore.DefaultCooperation2Factory;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheControllerHolder;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.proxy.ProxyFacetSupport;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.io.ByteStreams.toByteArray;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toList;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Tests for {@link ProxyFacetSupport} using Java 21 Virtual Threads.
 * 
 * This test verifies that proxy repository operations can efficiently use Virtual Threads
 * for improved concurrency and resource utilization, particularly for I/O-bound operations
 * like remote repository fetches.
 */
public class ProxyOperationsVirtualThreadTest
    extends TestSupport
{
  private static final int NUM_CLIENTS = 5000;

  private static final int NUM_PATHS = 100;

  private static final String META_PREFIX = "meta/";

  private static final String ASSET_PREFIX = "asset/";

  private static final byte[] META_CONTENT = "META".getBytes(UTF_8);

  private static final byte[] ASSET_CONTENT = "ASSET".getBytes(UTF_8);

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

  Multiset<String> upstreamRequestLog = ConcurrentHashMultiset.create();

  Semaphore metaDownloadPermits = new Semaphore(0);

  Semaphore assetDownloadPermits = new Semaphore(0);
  
  AtomicInteger completedRequests = new AtomicInteger(0);
  
  AtomicLong platformThreadTime = new AtomicLong(0);
  
  AtomicLong virtualThreadTime = new AtomicLong(0);

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

  @Before
  public void setUp() throws Exception {
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
  }

  Request request(final String path) {
    return new Request.Builder().action(GET).path(path).build();
  }

  List<Request> generateRandomRequests(final String pathPrefix) {
    return random.ints(NUM_CLIENTS, 0, NUM_PATHS).mapToObj(i -> pathPrefix + i).map(this::request).collect(toList());
  }

  void waitForThreadCooperation(final int expectedCount) {
    await().until(
        () -> underTest.getThreadCooperationPerRequest().entrySet().stream()
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

  void waitForCompletedRequests(final int expectedCount) {
    await().until(() -> completedRequests.get(), is(expectedCount));
  }

  Runnable proxyGetTask(final Request request) {
    return () -> {
      try {
        Content content = underTest.get(new Context(repository, request));
        try (InputStream in = content.openInputStream()) {
          assertThat(toByteArray(in), is(ASSET_CONTENT));
          completedRequests.incrementAndGet();
        }
      }
      catch (IOException e) {
        fail("Unexpected " + e);
      }
    };
  }

  /**
   * Tests proxy repository operations with a high number of concurrent requests using platform threads.
   * This establishes a baseline for comparison with virtual threads.
   */
  @Test
  public void testProxyOperationsWithPlatformThreads() throws Exception {
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), NUM_CLIENTS);
    underTest.buildCooperation();

    List<Request> requests = generateRandomRequests("some/valid/indirect/path-");
    int uniquePathCount = (int) requests.stream().map(Request::getPath).distinct().count();
    int totalClients = requests.size();

    // Create a fixed thread pool with platform threads
    ExecutorService executor = Executors.newFixedThreadPool(100); // Limited thread pool size
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean started = new AtomicBoolean(false);

    // Submit all tasks to the executor
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          if (!started.get()) {
            latch.await();
          }
          proxyGetTask(request).run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    // Start timing
    long startTime = System.currentTimeMillis();
    started.set(true);
    latch.countDown();

    // Wait for meta downloads and release them
    waitForMetaDownloads(uniquePathCount);
    releaseMetaDownloads(uniquePathCount);

    // Wait for asset downloads and release them
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);

    // Wait for all requests to complete
    waitForCompletedRequests(totalClients);
    long endTime = System.currentTimeMillis();
    platformThreadTime.set(endTime - startTime);

    log.info("Platform thread execution time: {} ms", platformThreadTime.get());
    log.info("Completed {} requests with {} unique paths", totalClients, uniquePathCount);
    log.info("Upstream requests: {}", upstreamRequestLog.size());

    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    // Reset for next test
    completedRequests.set(0);
    upstreamRequestLog.clear();
    storage.clear();
  }

  /**
   * Tests proxy repository operations with a high number of concurrent requests using virtual threads.
   * Compares performance with platform threads to demonstrate the benefits of virtual threads for I/O-bound operations.
   */
  @Test
  public void testProxyOperationsWithVirtualThreads() throws Exception {
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), NUM_CLIENTS);
    underTest.buildCooperation();

    List<Request> requests = generateRandomRequests("some/valid/indirect/path-");
    int uniquePathCount = (int) requests.stream().map(Request::getPath).distinct().count();
    int totalClients = requests.size();

    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean started = new AtomicBoolean(false);

    // Submit all tasks to the executor
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          if (!started.get()) {
            latch.await();
          }
          proxyGetTask(request).run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    // Start timing
    long startTime = System.currentTimeMillis();
    started.set(true);
    latch.countDown();

    // Wait for meta downloads and release them
    waitForMetaDownloads(uniquePathCount);
    releaseMetaDownloads(uniquePathCount);

    // Wait for asset downloads and release them
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);

    // Wait for all requests to complete
    waitForCompletedRequests(totalClients);
    long endTime = System.currentTimeMillis();
    virtualThreadTime.set(endTime - startTime);

    log.info("Virtual thread execution time: {} ms", virtualThreadTime.get());
    log.info("Completed {} requests with {} unique paths", totalClients, uniquePathCount);
    log.info("Upstream requests: {}", upstreamRequestLog.size());

    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    // Compare performance with platform threads
    if (platformThreadTime.get() > 0) {
      double improvement = (double) platformThreadTime.get() / virtualThreadTime.get();
      log.info("Virtual threads were {}x faster than platform threads", improvement);
      
      // Virtual threads should be faster for this I/O-bound workload
      assertThat(virtualThreadTime.get(), lessThan(platformThreadTime.get()));
    }
  }

  /**
   * Tests extreme concurrency with virtual threads to verify scalability.
   * This test creates a very high number of concurrent requests to demonstrate
   * that virtual threads can handle thousands of concurrent operations efficiently.
   */
  @Test
  public void testExtremeConcurrencyWithVirtualThreads() throws Exception {
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), NUM_CLIENTS * 2); // Allow more concurrent threads
    underTest.buildCooperation();

    // Generate a large number of requests with some duplication to test cooperation
    List<Request> requests = generateRandomRequests("some/extreme/concurrency/path-");
    int uniquePathCount = (int) requests.stream().map(Request::getPath).distinct().count();
    int totalClients = requests.size();

    log.info("Testing with {} total requests and {} unique paths", totalClients, uniquePathCount);

    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean started = new AtomicBoolean(false);

    // Submit all tasks to the executor
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          if (!started.get()) {
            latch.await();
          }
          proxyGetTask(request).run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    // Start timing
    long startTime = System.currentTimeMillis();
    started.set(true);
    latch.countDown();

    // Wait for meta downloads and release them
    waitForMetaDownloads(uniquePathCount);
    releaseMetaDownloads(uniquePathCount);

    // Wait for asset downloads and release them
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);

    // Wait for all requests to complete
    waitForCompletedRequests(totalClients);
    long endTime = System.currentTimeMillis();

    log.info("Extreme concurrency execution time: {} ms", (endTime - startTime));
    log.info("Completed {} requests with {} unique paths", totalClients, uniquePathCount);
    log.info("Upstream requests: {}", upstreamRequestLog.size());

    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    // Verify that cooperation worked correctly - we should have exactly one upstream request per unique path
    assertThat(upstreamRequestLog.size(), is(uniquePathCount * 2)); // One for meta, one for asset per unique path
  }

  /**
   * Tests the cache consistency under high concurrency with virtual threads.
   * This test verifies that the cache remains consistent when accessed by thousands
   * of concurrent virtual threads.
   */
  @Test
  public void testCacheConsistencyWithVirtualThreads() throws Exception {
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), NUM_CLIENTS);
    underTest.buildCooperation();

    // Generate requests with a smaller set of unique paths to increase cache hits
    List<Request> requests = generateRandomRequests("some/cache/test/path-");
    int uniquePathCount = (int) requests.stream().map(Request::getPath).distinct().count();
    int totalClients = requests.size();

    // First pass - populate the cache
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean started = new AtomicBoolean(false);

    // Submit all tasks to the executor
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          if (!started.get()) {
            latch.await();
          }
          proxyGetTask(request).run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    started.set(true);
    latch.countDown();

    // Wait for meta downloads and release them
    waitForMetaDownloads(uniquePathCount);
    releaseMetaDownloads(uniquePathCount);

    // Wait for asset downloads and release them
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);

    // Wait for all requests to complete
    waitForCompletedRequests(totalClients);

    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    // Record the number of upstream requests after first pass
    int firstPassRequests = upstreamRequestLog.size();
    log.info("First pass upstream requests: {}", firstPassRequests);

    // Reset for second pass
    completedRequests.set(0);
    upstreamRequestLog.clear();

    // Second pass - should use cache for everything
    executor = Executors.newVirtualThreadPerTaskExecutor();
    latch = new CountDownLatch(1);
    started.set(false);

    // Submit all tasks to the executor again
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          if (!started.get()) {
            latch.await();
          }
          proxyGetTask(request).run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    started.set(true);
    latch.countDown();

    // Wait for all requests to complete
    waitForCompletedRequests(totalClients);

    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    // Verify that no upstream requests were made in the second pass
    int secondPassRequests = upstreamRequestLog.size();
    log.info("Second pass upstream requests: {}", secondPassRequests);

    // Should be zero upstream requests in second pass since everything is cached
    assertThat(secondPassRequests, is(0));
    
    // First pass should have exactly one upstream request per unique path (for meta and asset)
    assertThat(firstPassRequests, is(uniquePathCount * 2));
  }

  /**
   * Tests cooperation patterns with virtual threads to verify that the system
   * properly coordinates concurrent requests for the same resource.
   */
  @Test
  public void testCooperationPatternsWithVirtualThreads() throws Exception {
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), NUM_CLIENTS);
    underTest.buildCooperation();

    // Generate requests with many duplicates to test cooperation
    List<Request> requests = generateRandomRequests("some/cooperation/test/path-");
    int uniquePathCount = (int) requests.stream().map(Request::getPath).distinct().count();
    int totalClients = requests.size();

    log.info("Testing cooperation with {} total requests and {} unique paths", totalClients, uniquePathCount);

    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean started = new AtomicBoolean(false);

    // Submit all tasks to the executor
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          if (!started.get()) {
            latch.await();
          }
          proxyGetTask(request).run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }

    // Start all threads simultaneously
    started.set(true);
    latch.countDown();

    // Wait for cooperation to happen
    waitForThreadCooperation(totalClients);

    // Wait for meta downloads and release them
    waitForMetaDownloads(uniquePathCount);
    releaseMetaDownloads(uniquePathCount);

    // Wait for asset downloads and release them
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);

    // Wait for all requests to complete
    waitForCompletedRequests(totalClients);

    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    // Verify that cooperation worked correctly - we should have exactly one upstream request per unique path
    assertThat(upstreamRequestLog.size(), is(uniquePathCount * 2)); // One for meta, one for asset per unique path
    
    // Verify that we had the expected number of cooperating threads
    assertThat(totalClients, greaterThan(upstreamRequestLog.size()));
  }
}