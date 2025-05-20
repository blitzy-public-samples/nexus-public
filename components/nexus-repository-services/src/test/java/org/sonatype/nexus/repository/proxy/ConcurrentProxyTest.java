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
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Concurrent {@link ProxyFacetSupport} tests.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class ConcurrentProxyTest
    extends TestSupport
{
  private static final int NUM_CLIENTS = 100;

  private static final int NUM_PATHS = 10;

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

  @BeforeEach
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

  @Test
  public void compareDownloadPerformanceBetweenThreadTypes() throws Exception {
    // Configure ProxyFacetSupport for testing
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, false, Duration.ofSeconds(0),
        Duration.ofSeconds(0), 0);
    underTest.buildCooperation();

    // Number of concurrent downloads to test
    int concurrentDownloads = 100;
    int iterations = 5;

    // Create thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();

    // Prepare request
    Request request = new Request.Builder().action(GET).path("performance/test/path").build();
    Context context = new Context(repository, request);

    // Metrics for timing
    AtomicLong virtualThreadTime = new AtomicLong(0);
    AtomicLong platformThreadTime = new AtomicLong(0);

    // Test with platform threads
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(concurrentDownloads, platformThreadFactory)) {
      for (int i = 0; i < iterations; i++) {
        CountDownLatch latch = new CountDownLatch(concurrentDownloads);
        long startTime = System.nanoTime();

        // Release permits in advance for this test
        releaseMetaDownloads(concurrentDownloads);
        releaseAssetDownloads(concurrentDownloads);

        // Submit tasks
        for (int j = 0; j < concurrentDownloads; j++) {
          platformExecutor.submit(() -> {
            try {
              underTest.get(context);
            }
            catch (IOException e) {
              // Ignore for test
            }
            finally {
              latch.countDown();
            }
          });
        }

        // Wait for completion
        latch.await(30, TimeUnit.SECONDS);
        platformThreadTime.addAndGet(System.nanoTime() - startTime);
      }
    }

    // Test with virtual threads
    try (ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      for (int i = 0; i < iterations; i++) {
        CountDownLatch latch = new CountDownLatch(concurrentDownloads);
        long startTime = System.nanoTime();

        // Release permits in advance for this test
        releaseMetaDownloads(concurrentDownloads);
        releaseAssetDownloads(concurrentDownloads);

        // Submit tasks
        for (int j = 0; j < concurrentDownloads; j++) {
          virtualExecutor.submit(() -> {
            try {
              underTest.get(context);
            }
            catch (IOException e) {
              // Ignore for test
            }
            finally {
              latch.countDown();
            }
          });
        }

        // Wait for completion
        latch.await(30, TimeUnit.SECONDS);
        virtualThreadTime.addAndGet(System.nanoTime() - startTime);
      }
    }

    // Calculate average times
    double avgPlatformTimeMs = platformThreadTime.get() / (iterations * 1_000_000.0);
    double avgVirtualTimeMs = virtualThreadTime.get() / (iterations * 1_000_000.0);

    log.info("Average download time with platform threads: {} ms", avgPlatformTimeMs);
    log.info("Average download time with virtual threads: {} ms", avgVirtualTimeMs);
    log.info("Performance improvement: {}%", ((avgPlatformTimeMs / avgVirtualTimeMs) - 1) * 100);

    // Virtual threads should perform better for I/O-bound operations
    assertThat("Virtual threads should perform better than platform threads for downloads",
        avgVirtualTimeMs, lessThan(avgPlatformTimeMs));
  }

  @Test
  public void detectThreadPinningInProxyOperations() throws Exception {
    // Configure ProxyFacetSupport for testing
    underTest.configureCooperation(cooperationFactory, cooperationFactory, false, false, false, Duration.ofSeconds(0),
        Duration.ofSeconds(0), 0);
    underTest.buildCooperation();

    // Create a request that will trigger proxy operations
    Request request = new Request.Builder().action(GET).path("pinning/test/path").build();
    Context context = new Context(repository, request);

    // Release permits in advance for this test
    releaseMetaDownloads(1);
    releaseAssetDownloads(1);

    // Create a virtual thread to execute the proxy operation
    AtomicBoolean completed = new AtomicBoolean(false);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);

    // Create a virtual thread to run the proxy operation
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        underTest.get(context);
        completed.set(true);
      }
      catch (Exception e) {
        log.error("Error in virtual thread task", e);
      }
      finally {
        latch.countDown();
      }
    });

    // Create a monitoring thread to check if the virtual thread is pinned
    Thread monitorThread = Thread.ofPlatform().start(() -> {
      try {
        // Wait for a short period to allow the virtual thread to start
        Thread.sleep(100);

        // Check if the virtual thread is still running but not making progress
        long startTime = System.currentTimeMillis();
        long timeoutMillis = 5000; // 5 seconds timeout

        while (!completed.get() && System.currentTimeMillis() - startTime < timeoutMillis) {
          // If the thread is alive but not making progress for a significant time, it might be pinned
          if (virtualThread.isAlive()) {
            Thread.sleep(100); // Check periodically
          }
          else {
            break; // Thread completed
          }
        }

        // If the task didn't complete within the timeout, it might be pinned
        if (!completed.get()) {
          log.warn("Potential thread pinning detected: Virtual thread blocked for {} ms", timeoutMillis);
          pinningDetected.set(true);
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    // Wait for the operation to complete
    latch.await(10, TimeUnit.SECONDS);
    monitorThread.join(1000);

    // Verify that the operation completed successfully
    assertThat("Proxy operation should complete successfully", completed.get(), is(true));

    // Verify that no thread pinning was detected
    // This is the key assertion - ProxyFacetSupport should be optimized to avoid thread pinning
    assertThat("ProxyFacetSupport operations should not cause thread pinning", pinningDetected.get(), is(false));
  }
}