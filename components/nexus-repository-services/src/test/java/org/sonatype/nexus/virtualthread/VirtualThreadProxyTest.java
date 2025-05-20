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
package org.sonatype.nexus.virtualthread;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Tests {@link ProxyFacetSupport} with Java 21 Virtual Threads under high-concurrency scenarios.
 * 
 * This test validates that proxy repository operations function correctly when using thousands
 * of virtual threads simultaneously, ensuring that caching, cooperation, throttling, and error
 * propagation work as expected with the new threading model.
 */
public class VirtualThreadProxyTest
    extends TestSupport
{
  // Significantly higher concurrency than the platform thread test
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

  AtomicInteger cooperationExceptionCount = new AtomicInteger();

  Multiset<String> upstreamRequestLog = ConcurrentHashMultiset.create();

  Semaphore metaDownloadPermits = new Semaphore(0);

  Semaphore assetDownloadPermits = new Semaphore(0);
  
  // Performance metrics
  AtomicLong virtualThreadStartTime = new AtomicLong();
  AtomicLong virtualThreadEndTime = new AtomicLong();
  AtomicLong platformThreadStartTime = new AtomicLong();
  AtomicLong platformThreadEndTime = new AtomicLong();

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
  
  /**
   * Creates a virtual thread factory for use in tests.
   */
  private ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().name("virtual-", 0).factory();
  }
  
  /**
   * Creates a platform thread factory for use in tests.
   */
  private ThreadFactory createPlatformThreadFactory() {
    return Thread.ofPlatform().name("platform-", 0).factory();
  }

  /**
   * Tests that download cooperation works correctly with virtual threads.
   * This test verifies that when using virtual threads, the cooperation mechanism
   * still properly limits upstream requests to one per unique path.
   */
  @Test
  public void downloadCooperationWithVirtualThreads() throws Exception {
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

    // Use virtual threads for the concurrent runner
    ConcurrentRunner runner = new ConcurrentRunner(iterations, 60, createVirtualThreadFactory());
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

  /**
   * Tests that thread limits are enforced correctly with virtual threads.
   * This test verifies that even with virtual threads, the cooperation mechanism
   * still properly limits the number of concurrent threads to the configured maximum.
   */
  @Test
  public void limitCooperatingVirtualThreads() throws Exception {
    int threadLimit = 100; // Higher limit for virtual threads

    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, Duration.ofSeconds(60),
        Duration.ofSeconds(10),
        threadLimit);
    underTest.buildCooperation();

    Request request = new Request.Builder().action(GET).path("some/fixed/path").build();

    // Use virtual threads for the concurrent runner
    ConcurrentRunner runner = new ConcurrentRunner(1, 60, createVirtualThreadFactory());
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
   * Compares the performance of virtual threads vs platform threads for proxy operations.
   * This test executes the same workload with both threading models and measures the difference.
   */
  @Test
  public void compareVirtualThreadsVsPlatformThreads() throws Exception {
    int concurrentClients = 1000;
    int iterations = 5;
    
    underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, 
        Duration.ofSeconds(60), Duration.ofSeconds(10), concurrentClients * 2);
    underTest.buildCooperation();
    
    List<Request> requests = generateRandomRequests("some/valid/indirect/path-");
    requests = requests.subList(0, concurrentClients); // Limit to specified number of clients
    
    int uniquePathCount = countUniquePaths(requests);
    CountDownLatch completionLatch = new CountDownLatch(1);
    
    // First run with platform threads
    ExecutorService platformExecutor = Executors.newFixedThreadPool(200, createPlatformThreadFactory());
    platformThreadStartTime.set(System.currentTimeMillis());
    
    for (Request request : requests) {
      platformExecutor.submit(() -> {
        try {
          Content content = underTest.get(new Context(repository, request));
          try (InputStream in = content.openInputStream()) {
            toByteArray(in); // Consume content
          }
        } catch (IOException e) {
          // Ignore for benchmark
        }
      });
    }
    
    // Wait for meta downloads and release them
    waitForMetaDownloads(1);
    releaseMetaDownloads(1);
    
    // Wait for asset downloads and release them
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);
    
    // Wait for completion and record end time
    platformExecutor.shutdown();
    platformExecutor.awaitTermination(30, TimeUnit.SECONDS);
    platformThreadEndTime.set(System.currentTimeMillis());
    
    // Clear storage to ensure fresh downloads for the next test
    storage.clear();
    upstreamRequestLog.clear();
    
    // Now run with virtual threads
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory());
    virtualThreadStartTime.set(System.currentTimeMillis());
    
    for (Request request : requests) {
      virtualExecutor.submit(() -> {
        try {
          Content content = underTest.get(new Context(repository, request));
          try (InputStream in = content.openInputStream()) {
            toByteArray(in); // Consume content
          }
        } catch (IOException e) {
          // Ignore for benchmark
        }
      });
    }
    
    // Wait for meta downloads and release them
    waitForMetaDownloads(1);
    releaseMetaDownloads(1);
    
    // Wait for asset downloads and release them
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);
    
    // Wait for completion and record end time
    virtualExecutor.shutdown();
    virtualExecutor.awaitTermination(30, TimeUnit.SECONDS);
    virtualThreadEndTime.set(System.currentTimeMillis());
    
    // Calculate and log performance metrics
    long platformTime = platformThreadEndTime.get() - platformThreadStartTime.get();
    long virtualTime = virtualThreadEndTime.get() - virtualThreadStartTime.get();
    
    log.info("Platform thread execution time: {} ms", platformTime);
    log.info("Virtual thread execution time: {} ms", virtualTime);
    log.info("Performance improvement: {}%", 
        platformTime > 0 ? (platformTime - virtualTime) * 100 / platformTime : 0);
    
    // Virtual threads should be faster for I/O bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O operations",
        virtualTime, lessThan(platformTime));
  }
  
  /**
   * Tests the scalability of virtual threads with a very high number of concurrent clients.
   * This test verifies that the system can handle thousands of concurrent virtual threads
   * without running into resource limitations that would affect platform threads.
   */
  @Test
  public void virtualThreadScalability() throws Exception {
    int massiveConcurrency = 10000; // This would be impractical with platform threads
    
    underTest.configureCooperation(cooperationFactory, cooperationFactory, true, false, true, 
        Duration.ofSeconds(60), Duration.ofSeconds(10), massiveConcurrency * 2);
    underTest.buildCooperation();
    
    // Generate a large number of requests to the same path to test cooperation
    List<Request> requests = new java.util.ArrayList<>(massiveConcurrency);
    for (int i = 0; i < massiveConcurrency; i++) {
      requests.add(request("massive/concurrency/test"));
    }
    
    // Use a virtual thread per task executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory());
    CountDownLatch completionLatch = new CountDownLatch(massiveConcurrency);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Start the timer
    long startTime = System.currentTimeMillis();
    
    // Submit all requests
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          Content content = underTest.get(new Context(repository, request));
          try (InputStream in = content.openInputStream()) {
            toByteArray(in); // Consume content
          }
          successCount.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for asset downloads and release them
    waitForAssetDownloads(1); // Only one unique path
    releaseAssetDownloads(1);
    
    // Wait for all tasks to complete or timeout
    boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    
    // Shutdown the executor
    executor.shutdown();
    
    // Log results
    log.info("Virtual thread scalability test completed: {}", completed);
    log.info("Successful requests: {}", successCount.get());
    log.info("Failed requests: {}", errorCount.get());
    log.info("Total execution time: {} ms", endTime - startTime);
    log.info("Throughput: {} requests/second", 
        (endTime > startTime) ? (successCount.get() * 1000L / (endTime - startTime)) : 0);
    
    // Verify results
    assertThat("All requests should complete successfully", successCount.get(), is(massiveConcurrency));
    assertThat("There should be no errors", errorCount.get(), is(0));
    assertThat("Execution should complete within timeout", completed, is(true));
    
    // Verify that only one upstream request was made due to cooperation
    assertThat(upstreamRequestLog.count(ASSET_PREFIX + "massive/concurrency/test"), is(1));
  }
  
  /**
   * Tests the behavior of virtual threads when handling errors during remote operations.
   * This test verifies that errors are properly propagated when using virtual threads,
   * and that the system remains stable even when many virtual threads encounter errors.
   */
  @Test
  public void errorPropagationWithVirtualThreads() throws Exception {
    int concurrentClients = 2000;
    
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, true, 
        Duration.ofSeconds(60), Duration.ofSeconds(10), concurrentClients * 2);
    underTest.buildCooperation();
    
    // Generate requests that will result in errors
    List<Request> requests = new java.util.ArrayList<>(concurrentClients);
    for (int i = 0; i < concurrentClients; i++) {
      requests.add(request("error/broken/path-" + i));
    }
    
    // Use a virtual thread per task executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory());
    CountDownLatch completionLatch = new CountDownLatch(concurrentClients);
    AtomicInteger expectedErrorCount = new AtomicInteger(0);
    AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
    
    // Submit all requests
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          underTest.get(new Context(repository, request));
          unexpectedErrorCount.incrementAndGet(); // Should not reach here
        } catch (IOException e) {
          if ("oops".equals(e.getMessage())) {
            expectedErrorCount.incrementAndGet();
          } else {
            unexpectedErrorCount.incrementAndGet();
          }
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Release all download permits
    int uniquePathCount = countUniquePaths(requests);
    waitForAssetDownloads(uniquePathCount);
    releaseAssetDownloads(uniquePathCount);
    
    // Wait for all tasks to complete
    completionLatch.await(30, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertThat("All requests should result in expected errors", 
        expectedErrorCount.get(), is(concurrentClients));
    assertThat("There should be no unexpected errors", 
        unexpectedErrorCount.get(), is(0));
    
    // Verify that each unique path resulted in one upstream request
    assertThat(upstreamRequestLog.size(), is(uniquePathCount));
  }
  
  /**
   * Tests the memory efficiency of virtual threads compared to platform threads.
   * This test creates a large number of threads and measures memory usage to verify
   * that virtual threads consume significantly less memory than platform threads.
   */
  @Test
  public void memoryEfficiencyOfVirtualThreads() throws Exception {
    int threadCount = 5000;
    
    // Force garbage collection to get a clean baseline
    System.gc();
    Thread.sleep(1000);
    long baselineMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create and start platform threads
    Thread[] platformThreads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      platformThreads[i] = Thread.ofPlatform().name("platform-" + i).start(() -> {
        try {
          Thread.sleep(5000); // Keep thread alive
        } catch (InterruptedException e) {
          // Ignore
        }
      });
    }
    
    // Measure memory after platform threads
    System.gc();
    Thread.sleep(1000);
    long platformThreadMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - baselineMemory;
    
    // Wait for platform threads to finish
    for (Thread thread : platformThreads) {
      thread.join(10000);
    }
    
    // Force garbage collection
    System.gc();
    Thread.sleep(1000);
    
    // Create and start virtual threads
    Thread[] virtualThreads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      virtualThreads[i] = Thread.ofVirtual().name("virtual-" + i).start(() -> {
        try {
          Thread.sleep(5000); // Keep thread alive
        } catch (InterruptedException e) {
          // Ignore
        }
      });
    }
    
    // Measure memory after virtual threads
    System.gc();
    Thread.sleep(1000);
    long virtualThreadMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - baselineMemory;
    
    // Wait for virtual threads to finish
    for (Thread thread : virtualThreads) {
      thread.join(10000);
    }
    
    // Log memory usage
    log.info("Memory used by {} platform threads: {} bytes", threadCount, platformThreadMemory);
    log.info("Memory used by {} virtual threads: {} bytes", threadCount, virtualThreadMemory);
    log.info("Memory savings with virtual threads: {}%", 
        platformThreadMemory > 0 ? (platformThreadMemory - virtualThreadMemory) * 100 / platformThreadMemory : 0);
    
    // Virtual threads should use significantly less memory
    assertThat("Virtual threads should use less memory than platform threads",
        virtualThreadMemory, lessThan(platformThreadMemory));
    
    // Virtual threads should use at least 80% less memory
    assertThat("Virtual threads should use at least 80% less memory",
        (platformThreadMemory - virtualThreadMemory) * 100 / platformThreadMemory, greaterThan(80L));
  }
}