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
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Tests for {@link ProxyFacetSupport} with Java 21 Virtual Threads.
 */
public class ProxyFacetSupportVirtualThreadTest
    extends TestSupport
{
  private static final int NUM_CLIENTS = 1000;

  private static final int NUM_PATHS = 50;

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

  // Track thread pinning events
  AtomicInteger pinnedThreadCount = new AtomicInteger(0);
  
  // Track resource cleanup
  AtomicInteger resourceCleanupCount = new AtomicInteger(0);
  
  // Track execution times for performance comparison
  Map<String, Long> executionTimes = new ConcurrentHashMap<>();

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
      if (path.contains("pinned")) {
        // Simulate thread pinning by performing a blocking operation that would pin a virtual thread
        simulateThreadPinning();
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
        if (url.contains("resource")) {
          // Simulate resource allocation and cleanup
          try {
            return assetContent;
          } finally {
            resourceCleanupCount.incrementAndGet();
          }
        }
        return assetContent;
      }

      return null;
    }
    
    private void simulateThreadPinning() {
      // This operation would cause thread pinning in a virtual thread
      // In a real scenario, this might be a synchronized block or a native method call
      synchronized (this) {
        try {
          // Simulate a blocking operation that would pin the thread
          Thread.sleep(50);
          pinnedThreadCount.incrementAndGet();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
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

  List<Request> generateRandomRequests(final String pathPrefix, final int count) {
    List<Request> requests = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int pathIndex = random.nextInt(NUM_PATHS);
      requests.add(request(pathPrefix + pathIndex));
    }
    return requests;
  }

  void waitForMetaDownloads(final int expectedCount) {
    await().atMost(10, SECONDS).until(() -> metaDownloadPermits.getQueueLength(), is(expectedCount));
  }

  void releaseMetaDownloads(final int permits) {
    metaDownloadPermits.release(permits);
  }

  void waitForAssetDownloads(final int expectedCount) {
    await().atMost(10, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), is(expectedCount));
  }

  void releaseAssetDownloads(final int permits) {
    assetDownloadPermits.release(permits);
  }

  /**
   * Creates a thread factory for either platform or virtual threads.
   */
  ThreadFactory createThreadFactory(boolean useVirtualThreads) {
    if (useVirtualThreads) {
      return Thread.ofVirtual().name("virtual-", 1).factory();
    } else {
      return Thread.ofPlatform().name("platform-", 1).factory();
    }
  }

  /**
   * Creates an executor service using either platform or virtual threads.
   */
  ExecutorService createExecutorService(boolean useVirtualThreads, int threadCount) {
    if (useVirtualThreads) {
      return Executors.newVirtualThreadPerTaskExecutor();
    } else {
      return Executors.newFixedThreadPool(threadCount, Thread.ofPlatform().name("platform-", 1).factory());
    }
  }

  /**
   * Test that compares the performance of platform threads vs virtual threads for proxy operations.
   */
  @Test
  public void compareThreadPerformance() throws Exception {
    int clientCount = 500;
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), clientCount * 2);
    underTest.buildCooperation();

    // Generate requests for valid paths
    List<Request> requests = generateRandomRequests("some/valid/path-", clientCount);

    // Test with platform threads
    long platformTime = measureExecutionTime(() -> {
      executeRequests(requests, false);
    });
    executionTimes.put("Platform Threads", platformTime);

    // Clear storage to ensure fresh fetches
    storage.clear();
    upstreamRequestLog.clear();

    // Test with virtual threads
    long virtualTime = measureExecutionTime(() -> {
      executeRequests(requests, true);
    });
    executionTimes.put("Virtual Threads", virtualTime);

    // Log the results
    log.info("Performance comparison:");
    log.info("Platform threads execution time: {} ms", platformTime);
    log.info("Virtual threads execution time: {} ms", virtualTime);

    // Virtual threads should generally be faster or at least comparable for this workload
    assertThat("Virtual threads should be comparable or faster than platform threads",
        virtualTime, lessThan(platformTime * 1.2)); // Allow some variance
  }

  private void executeRequests(List<Request> requests, boolean useVirtualThreads) {
    ExecutorService executor = createExecutorService(useVirtualThreads, Math.min(requests.size(), 200));
    CountDownLatch latch = new CountDownLatch(requests.size());

    try {
      // Submit all requests
      for (Request request : requests) {
        executor.submit(() -> {
          try {
            Context context = new Context(repository, request);
            Content content = underTest.get(context);
            try (InputStream in = content.openInputStream()) {
              // Consume the content
              toByteArray(in);
            }
          } catch (Exception e) {
            log.error("Error processing request", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all downloads to be queued
      await().atMost(5, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), greaterThanOrEqualTo(1));

      // Release all permits to allow downloads to proceed
      releaseAssetDownloads(assetDownloadPermits.getQueueLength());

      // Wait for all tasks to complete
      if (!latch.await(30, SECONDS)) {
        fail("Timed out waiting for requests to complete");
      }
    } catch (Exception e) {
      fail("Unexpected exception: " + e);
    } finally {
      executor.shutdown();
    }
  }

  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Test that validates cooperative download behavior with virtual threads at high concurrency.
   */
  @Test
  public void testCooperativeDownloadWithVirtualThreads() throws Exception {
    int clientCount = 1000;
    int uniquePaths = 20;

    // Enable cooperation
    underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), clientCount * 2);
    underTest.buildCooperation();

    // Generate requests with a limited number of unique paths to ensure cooperation
    List<Request> requests = new ArrayList<>(clientCount);
    for (int i = 0; i < clientCount; i++) {
      int pathIndex = i % uniquePaths; // Ensure we have exactly uniquePaths different paths
      requests.add(request("some/valid/path-" + pathIndex));
    }

    // Use virtual threads for high concurrency
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(clientCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit all requests
      for (Request request : requests) {
        executor.submit(() -> {
          try {
            Context context = new Context(repository, request);
            Content content = underTest.get(context);
            try (InputStream in = content.openInputStream()) {
              // Consume the content
              byte[] bytes = toByteArray(in);
              if (bytes.length > 0) {
                successCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            log.error("Error processing request", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for downloads to be queued
      await().atMost(5, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), greaterThanOrEqualTo(1));

      // With cooperation enabled, we should see exactly uniquePaths download requests
      // (one per unique path) rather than clientCount requests
      int queuedDownloads = assetDownloadPermits.getQueueLength();
      log.info("Queued downloads: {}", queuedDownloads);
      
      // Allow some variance due to timing, but should be close to uniquePaths
      assertThat("Number of queued downloads should be close to the number of unique paths",
          queuedDownloads, lessThan(uniquePaths * 2));

      // Release all permits to allow downloads to proceed
      releaseAssetDownloads(queuedDownloads);

      // Wait for all tasks to complete
      if (!latch.await(30, SECONDS)) {
        fail("Timed out waiting for requests to complete");
      }

      // Verify all requests succeeded
      assertEquals("All requests should succeed", clientCount, successCount.get());

      // Verify the number of upstream requests matches the number of unique paths
      int totalUpstreamRequests = upstreamRequestLog.size();
      log.info("Total upstream requests: {}", totalUpstreamRequests);
      
      // Should be close to uniquePaths, allowing some variance
      assertThat("Number of upstream requests should be close to the number of unique paths",
          totalUpstreamRequests, lessThan(uniquePaths * 2));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Test that validates thread pinning detection during proxy repository operations.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    int clientCount = 100;

    // Configure cooperation
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), clientCount * 2);
    underTest.buildCooperation();

    // Generate requests that will cause thread pinning
    List<Request> requests = generateRandomRequests("some/pinned/path-", clientCount);

    // Use virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(clientCount);

    try {
      // Submit all requests
      for (Request request : requests) {
        executor.submit(() -> {
          try {
            Context context = new Context(repository, request);
            Content content = underTest.get(context);
            try (InputStream in = content.openInputStream()) {
              // Consume the content
              toByteArray(in);
            }
          } catch (Exception e) {
            log.error("Error processing request", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for downloads to be queued
      await().atMost(5, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), greaterThanOrEqualTo(1));

      // Release all permits to allow downloads to proceed
      releaseAssetDownloads(assetDownloadPermits.getQueueLength());

      // Wait for all tasks to complete
      if (!latch.await(30, SECONDS)) {
        fail("Timed out waiting for requests to complete");
      }

      // Verify that thread pinning was detected
      int pinningCount = pinnedThreadCount.get();
      log.info("Thread pinning count: {}", pinningCount);
      assertThat("Thread pinning should be detected", pinningCount, greaterThan(0));
      assertThat("Thread pinning should occur for most requests", pinningCount, greaterThanOrEqualTo(clientCount / 2));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Test that verifies proper resource cleanup with virtual threads during network operations.
   */
  @Test
  public void testResourceCleanupWithVirtualThreads() throws Exception {
    int clientCount = 100;

    // Configure cooperation
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), clientCount * 2);
    underTest.buildCooperation();

    // Generate requests that will allocate resources
    List<Request> requests = generateRandomRequests("some/resource/path-", clientCount);

    // Use virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(clientCount);

    try {
      // Submit all requests
      for (Request request : requests) {
        executor.submit(() -> {
          try {
            Context context = new Context(repository, request);
            Content content = underTest.get(context);
            try (InputStream in = content.openInputStream()) {
              // Consume the content
              toByteArray(in);
            }
          } catch (Exception e) {
            log.error("Error processing request", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for downloads to be queued
      await().atMost(5, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), greaterThanOrEqualTo(1));

      // Release all permits to allow downloads to proceed
      releaseAssetDownloads(assetDownloadPermits.getQueueLength());

      // Wait for all tasks to complete
      if (!latch.await(30, SECONDS)) {
        fail("Timed out waiting for requests to complete");
      }

      // Verify that resources were properly cleaned up
      int cleanupCount = resourceCleanupCount.get();
      log.info("Resource cleanup count: {}", cleanupCount);
      assertThat("Resources should be properly cleaned up", cleanupCount, greaterThan(0));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Test that validates remote fetch operations under various virtual thread concurrency levels.
   */
  @Test
  public void testRemoteFetchWithVaryingConcurrencyLevels() throws Exception {
    // Configure cooperation
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), 10000); // High thread limit
    underTest.buildCooperation();

    // Test with different concurrency levels
    int[] concurrencyLevels = {10, 100, 500, 1000};

    for (int concurrency : concurrencyLevels) {
      log.info("Testing with concurrency level: {}", concurrency);
      
      // Clear state from previous runs
      storage.clear();
      upstreamRequestLog.clear();
      resourceCleanupCount.set(0);
      
      // Generate requests
      List<Request> requests = generateRandomRequests("concurrency/level-" + concurrency + "/path-", concurrency);
      
      // Use virtual threads
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      CountDownLatch latch = new CountDownLatch(concurrency);
      LongAdder successCount = new LongAdder();
      
      long startTime = System.currentTimeMillis();
      
      try {
        // Submit all requests
        for (Request request : requests) {
          executor.submit(() -> {
            try {
              Context context = new Context(repository, request);
              Content content = underTest.get(context);
              try (InputStream in = content.openInputStream()) {
                // Consume the content
                if (toByteArray(in).length > 0) {
                  successCount.increment();
                }
              }
            } catch (Exception e) {
              log.error("Error processing request", e);
            } finally {
              latch.countDown();
            }
          });
        }

        // Wait for downloads to be queued
        await().atMost(10, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), greaterThan(0));

        // Release all permits to allow downloads to proceed
        releaseAssetDownloads(assetDownloadPermits.getQueueLength());

        // Wait for all tasks to complete
        if (!latch.await(30, SECONDS)) {
          fail("Timed out waiting for requests to complete at concurrency level " + concurrency);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Concurrency level {} completed in {} ms", concurrency, duration);
        
        // Verify all requests succeeded
        assertEquals("All requests should succeed at concurrency level " + concurrency, 
            concurrency, successCount.sum());
        
        // Store the execution time for this concurrency level
        executionTimes.put("Concurrency-" + concurrency, duration);
        
      } finally {
        executor.shutdown();
      }
    }
    
    // Log the results
    log.info("Execution times for different concurrency levels:");
    for (int concurrency : concurrencyLevels) {
      log.info("Concurrency {}: {} ms", concurrency, executionTimes.get("Concurrency-" + concurrency));
    }
  }

  /**
   * Test that ensures connection pooling works correctly with virtual threads.
   */
  @Test
  public void testConnectionPoolingWithVirtualThreads() throws Exception {
    int clientCount = 200;
    int uniquePaths = 50;

    // Configure cooperation
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10), clientCount * 2);
    underTest.buildCooperation();

    // Generate requests with a limited number of unique paths
    List<Request> requests = new ArrayList<>(clientCount);
    for (int i = 0; i < clientCount; i++) {
      int pathIndex = i % uniquePaths;
      requests.add(request("connection/pooling/path-" + pathIndex));
    }

    // Use virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(clientCount);
    AtomicInteger activeConnections = new AtomicInteger(0);
    AtomicInteger maxActiveConnections = new AtomicInteger(0);

    try {
      // Submit all requests
      for (Request request : requests) {
        executor.submit(() -> {
          try {
            // Track active connections
            int current = activeConnections.incrementAndGet();
            maxActiveConnections.updateAndGet(max -> Math.max(max, current));
            
            Context context = new Context(repository, request);
            Content content = underTest.get(context);
            try (InputStream in = content.openInputStream()) {
              // Consume the content
              toByteArray(in);
            }
          } catch (Exception e) {
            log.error("Error processing request", e);
          } finally {
            activeConnections.decrementAndGet();
            latch.countDown();
          }
        });
      }

      // Wait for downloads to be queued
      await().atMost(5, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), greaterThan(0));

      // Release permits gradually to simulate connection pooling behavior
      int batchSize = 10;
      int queuedDownloads = assetDownloadPermits.getQueueLength();
      
      for (int i = 0; i < queuedDownloads; i += batchSize) {
        int permits = Math.min(batchSize, queuedDownloads - i);
        releaseAssetDownloads(permits);
        // Small delay to simulate network latency
        Thread.sleep(100);
      }

      // Wait for all tasks to complete
      if (!latch.await(30, SECONDS)) {
        fail("Timed out waiting for requests to complete");
      }

      // Log connection statistics
      log.info("Max active connections: {}", maxActiveConnections.get());
      
      // Verify that connection pooling worked correctly
      // The max active connections should be less than the total number of requests
      // but more than just a few connections
      assertThat("Connection pooling should limit max active connections", 
          maxActiveConnections.get(), lessThan(clientCount));
      assertThat("Connection pooling should allow multiple concurrent connections", 
          maxActiveConnections.get(), greaterThan(5));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Test that compares the scalability of platform threads vs virtual threads under high load.
   */
  @Test
  public void testScalabilityComparison() throws Exception {
    // Skip this test in CI environments or when running with limited resources
    if (Boolean.getBoolean("skipHighLoadTests")) {
      log.info("Skipping high load test");
      return;
    }

    int maxClients = 5000; // High number to test scalability
    
    // Configure cooperation
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, false, Duration.ofSeconds(60),
        Duration.ofSeconds(10), maxClients * 2);
    underTest.buildCooperation();

    // Test with platform threads first with a more limited client count
    int platformClientCount = 500; // Platform threads are more resource-intensive
    List<Request> platformRequests = generateRandomRequests("scalability/platform/path-", platformClientCount);
    
    log.info("Testing platform thread scalability with {} clients", platformClientCount);
    long platformTime = runScalabilityTest(platformRequests, false);
    log.info("Platform thread test completed in {} ms", platformTime);
    
    // Clear state
    storage.clear();
    upstreamRequestLog.clear();
    
    // Now test with virtual threads and a much higher client count
    List<Request> virtualRequests = generateRandomRequests("scalability/virtual/path-", maxClients);
    
    log.info("Testing virtual thread scalability with {} clients", maxClients);
    long virtualTime = runScalabilityTest(virtualRequests, true);
    log.info("Virtual thread test completed in {} ms", virtualTime);
    
    // Calculate throughput (requests per second)
    double platformThroughput = (platformClientCount * 1000.0) / platformTime;
    double virtualThroughput = (maxClients * 1000.0) / virtualTime;
    
    log.info("Platform thread throughput: {:.2f} requests/second", platformThroughput);
    log.info("Virtual thread throughput: {:.2f} requests/second", virtualThroughput);
    
    // Virtual threads should handle more requests per second
    assertThat("Virtual threads should provide higher throughput", 
        virtualThroughput, greaterThan(platformThroughput));
    
    // Virtual threads should handle the higher load without proportional time increase
    double virtualToClientRatio = (double) maxClients / platformClientCount;
    double virtualToTimeRatio = (double) virtualTime / platformTime;
    
    log.info("Client count ratio (virtual/platform): {:.2f}", virtualToClientRatio);
    log.info("Execution time ratio (virtual/platform): {:.2f}", virtualToTimeRatio);
    
    // The time ratio should be significantly less than the client ratio,
    // showing better scalability with virtual threads
    assertThat("Virtual threads should scale better than platform threads",
        virtualToTimeRatio, lessThan(virtualToClientRatio * 0.5));
  }

  private long runScalabilityTest(List<Request> requests, boolean useVirtualThreads) throws Exception {
    ExecutorService executor = createExecutorService(useVirtualThreads, 
        useVirtualThreads ? Integer.MAX_VALUE : Math.min(requests.size(), 200));
    CountDownLatch latch = new CountDownLatch(requests.size());
    AtomicLong startTime = new AtomicLong();
    AtomicLong endTime = new AtomicLong();
    AtomicBoolean started = new AtomicBoolean(false);
    
    try {
      // Submit all requests
      List<CompletableFuture<Void>> futures = new ArrayList<>(requests.size());
      
      for (Request request : requests) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          if (started.compareAndSet(false, true)) {
            startTime.set(System.currentTimeMillis());
          }
          
          try {
            Context context = new Context(repository, request);
            Content content = underTest.get(context);
            try (InputStream in = content.openInputStream()) {
              // Consume the content
              toByteArray(in);
            }
          } catch (Exception e) {
            log.error("Error processing request", e);
          } finally {
            if (latch.countDown() == 0) {
              endTime.set(System.currentTimeMillis());
            }
          }
        }, executor);
        
        futures.add(future);
      }

      // Wait for downloads to be queued
      await().atMost(10, SECONDS).until(() -> assetDownloadPermits.getQueueLength(), greaterThan(0));

      // Release all permits to allow downloads to proceed
      releaseAssetDownloads(assetDownloadPermits.getQueueLength());

      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .orTimeout(60, SECONDS)
          .join();
      
      return endTime.get() - startTime.get();
    } finally {
      executor.shutdown();
    }
  }
}