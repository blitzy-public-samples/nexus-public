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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.sonatype.nexus.repository.proxy.ProxyFacetSupport;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;

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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Tests proxy repository operations with Java 21 Virtual Threads.
 * 
 * This test validates that proxy repositories can efficiently handle thousands of concurrent
 * requests using virtual threads, significantly improving throughput and resource utilization
 * for I/O-bound operations like proxying remote repositories.
 */
public class ProxyOperationsVirtualThreadTest
    extends TestSupport
{
  private static final int SMALL_CONCURRENCY = 100;
  private static final int MEDIUM_CONCURRENCY = 1_000;
  private static final int LARGE_CONCURRENCY = 10_000;
  
  private static final int REMOTE_LATENCY_MS = 100; // simulated remote repository latency
  private static final int REMOTE_TIMEOUT_MS = 5000; // simulated remote repository timeout
  
  private static final byte[] CONTENT_BYTES = "Test content for proxy repository".getBytes(UTF_8);
  
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
  Content content;
  
  @Mock
  EventManager eventManager;
  
  @Mock
  Format format;
  
  Cooperation2Factory cooperationFactory = new DefaultCooperation2Factory();
  
  Random random = new Random(42); // fixed seed for reproducibility
  
  Map<String, Content> storage = new ConcurrentHashMap<>();
  
  Map<String, AtomicInteger> remoteCallCounters = new ConcurrentHashMap<>();
  
  Semaphore remoteCallPermits = new Semaphore(Integer.MAX_VALUE);
  
  LongAdder totalRemoteCalls = new LongAdder();
  
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
      return "http://remote-repo/" + context.getRequest().getPath();
    }
    
    @Override
    protected Content fetch(final String url, final Context context, final Content stale) throws IOException {
      String path = context.getRequest().getPath();
      
      // Track remote call counts
      remoteCallCounters.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
      totalRemoteCalls.increment();
      
      try {
        // Acquire permit (used to control concurrency in tests)
        if (!remoteCallPermits.tryAcquire(REMOTE_TIMEOUT_MS, MILLISECONDS)) {
          throw new IOException("Remote call timed out for " + url);
        }
        
        try {
          // Simulate remote repository latency
          Thread.sleep(REMOTE_LATENCY_MS);
          
          // Simulate errors for paths containing "error"
          if (path.contains("error")) {
            throw new IOException("Simulated remote error for " + url);
          }
          
          // Return mock content
          return content;
        }
        finally {
          remoteCallPermits.release();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while fetching " + url, e);
      }
    }
  };
  
  @Before
  public void setUp() throws Exception {
    when(attributesMap.get(CacheInfo.class)).thenReturn(cacheInfo);
    
    when(content.getAttributes()).thenReturn(attributesMap);
    when(content.openInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(CONTENT_BYTES));
    
    when(cacheController.isStale(cacheInfo)).thenReturn(false);
    when(cacheControllerHolder.getContentCacheController()).thenReturn(cacheController);
    
    when(repository.getName()).thenReturn("test-repo");
    when(format.getValue()).thenReturn("raw");
    when(repository.getFormat()).thenReturn(format);
    
    underTest.installDependencies(eventManager);
    underTest.cacheControllerHolder = cacheControllerHolder;
    underTest.attach(repository);
  }
  
  /**
   * Creates a request for the given path.
   */
  private Request request(final String path) {
    return new Request.Builder().action(GET).path(path).build();
  }
  
  /**
   * Generates a list of random requests with the given prefix and count.
   */
  private List<Request> generateRandomRequests(final String pathPrefix, final int count, final int uniquePaths) {
    List<Request> requests = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int pathIndex = random.nextInt(uniquePaths);
      requests.add(request(pathPrefix + pathIndex));
    }
    return requests;
  }
  
  /**
   * Executes the given requests using the specified executor service and returns the execution time in milliseconds.
   */
  private long executeRequests(final List<Request> requests, final ExecutorService executor) throws Exception {
    int requestCount = requests.size();
    CountDownLatch latch = new CountDownLatch(requestCount);
    
    Instant start = Instant.now();
    
    for (Request request : requests) {
      executor.submit(() -> {
        try {
          Content content = underTest.get(new Context(repository, request));
          try (InputStream in = content.openInputStream()) {
            byte[] bytes = toByteArray(in);
            assertThat(bytes, is(CONTENT_BYTES));
          }
        }
        catch (IOException e) {
          // Expected for error paths
          if (!request.getPath().contains("error")) {
            fail("Unexpected exception: " + e);
          }
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    if (!latch.await(30, SECONDS)) {
      fail("Timed out waiting for requests to complete");
    }
    
    return Duration.between(start, Instant.now()).toMillis();
  }
  
  /**
   * Creates an executor service with the specified number of threads.
   * Uses platform threads or virtual threads based on the useVirtualThreads parameter.
   */
  private ExecutorService createExecutor(final int threadCount, final boolean useVirtualThreads) {
    if (useVirtualThreads) {
      return Executors.newVirtualThreadPerTaskExecutor();
    }
    else {
      return Executors.newFixedThreadPool(threadCount, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();
        
        @Override
        public Thread newThread(final Runnable r) {
          Thread thread = new Thread(r);
          thread.setName("platform-thread-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        }
      });
    }
  }
  
  /**
   * Runs a performance test with the given parameters and returns the execution time.
   */
  private long runPerformanceTest(
      final int concurrency,
      final int uniquePaths,
      final boolean useVirtualThreads,
      final boolean enableCooperation,
      final boolean preCacheContent) throws Exception
  {
    // Clear state from previous tests
    storage.clear();
    remoteCallCounters.clear();
    totalRemoteCalls.reset();
    
    // Configure cooperation
    underTest.configureCooperation(
        cooperationFactory,
        cooperationFactory,
        false,  // clustered cooperation
        false,  // clustered
        enableCooperation,  // enabled
        Duration.ofSeconds(60),  // major timeout
        Duration.ofSeconds(10),  // minor timeout
        concurrency  // max threads
    );
    underTest.buildCooperation();
    
    // Pre-cache content if requested
    if (preCacheContent) {
      for (int i = 0; i < uniquePaths; i++) {
        String path = "path-" + i;
        storage.put(path, content);
      }
    }
    
    // Generate requests
    List<Request> requests = generateRandomRequests("path-", concurrency, uniquePaths);
    
    // Create executor
    try (ExecutorService executor = createExecutor(concurrency, useVirtualThreads)) {
      // Execute requests and measure time
      return executeRequests(requests, executor);
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for a small number of concurrent requests.
   */
  @Test
  public void testSmallConcurrencyPerformance() throws Exception {
    int concurrency = SMALL_CONCURRENCY;
    int uniquePaths = 10;
    
    // Run with platform threads
    long platformTime = runPerformanceTest(concurrency, uniquePaths, false, true, false);
    log.info("Platform threads execution time (small concurrency): {} ms", platformTime);
    
    // Run with virtual threads
    long virtualTime = runPerformanceTest(concurrency, uniquePaths, true, true, false);
    log.info("Virtual threads execution time (small concurrency): {} ms", virtualTime);
    
    // For small concurrency, times should be comparable (virtual might be slightly faster)
    // but we don't make strict assertions as performance can vary between environments
    log.info("Performance ratio (platform/virtual): {}", (double) platformTime / virtualTime);
  }
  
  /**
   * Compares performance between platform threads and virtual threads for a medium number of concurrent requests.
   */
  @Test
  public void testMediumConcurrencyPerformance() throws Exception {
    int concurrency = MEDIUM_CONCURRENCY;
    int uniquePaths = 100;
    
    // Run with platform threads
    long platformTime = runPerformanceTest(concurrency, uniquePaths, false, true, false);
    log.info("Platform threads execution time (medium concurrency): {} ms", platformTime);
    
    // Run with virtual threads
    long virtualTime = runPerformanceTest(concurrency, uniquePaths, true, true, false);
    log.info("Virtual threads execution time (medium concurrency): {} ms", virtualTime);
    
    // Virtual threads should be noticeably faster with medium concurrency
    log.info("Performance ratio (platform/virtual): {}", (double) platformTime / virtualTime);
    assertThat("Virtual threads should be faster than platform threads with medium concurrency",
        platformTime, greaterThan(virtualTime));
  }
  
  /**
   * Tests performance with a large number of concurrent requests, which is only practical with virtual threads.
   */
  @Test
  public void testLargeConcurrencyPerformance() throws Exception {
    int concurrency = LARGE_CONCURRENCY;
    int uniquePaths = 1000;
    
    // Run with virtual threads
    long virtualTime = runPerformanceTest(concurrency, uniquePaths, true, true, false);
    log.info("Virtual threads execution time (large concurrency): {} ms", virtualTime);
    
    // Verify that we can handle this level of concurrency with virtual threads
    // We don't run with platform threads as it would likely cause resource exhaustion
  }
  
  /**
   * Tests the effect of cooperation on remote call reduction with virtual threads.
   */
  @Test
  public void testCooperationEffect() throws Exception {
    int concurrency = MEDIUM_CONCURRENCY;
    int uniquePaths = 50; // Small number of unique paths to maximize cooperation opportunities
    
    // Run without cooperation
    runPerformanceTest(concurrency, uniquePaths, true, false, false);
    long remoteCallsWithoutCooperation = totalRemoteCalls.sum();
    log.info("Remote calls without cooperation: {}", remoteCallsWithoutCooperation);
    
    // Run with cooperation
    runPerformanceTest(concurrency, uniquePaths, true, true, false);
    long remoteCallsWithCooperation = totalRemoteCalls.sum();
    log.info("Remote calls with cooperation: {}", remoteCallsWithCooperation);
    
    // Cooperation should significantly reduce remote calls
    log.info("Remote call reduction ratio: {}", (double) remoteCallsWithoutCooperation / remoteCallsWithCooperation);
    assertThat("Cooperation should reduce remote calls",
        remoteCallsWithCooperation, lessThan(remoteCallsWithoutCooperation));
  }
  
  /**
   * Tests caching behavior with virtual threads.
   */
  @Test
  public void testCachingBehavior() throws Exception {
    int concurrency = MEDIUM_CONCURRENCY;
    int uniquePaths = 100;
    
    // First run without pre-cached content
    runPerformanceTest(concurrency, uniquePaths, true, true, false);
    long remoteCallsFirstRun = totalRemoteCalls.sum();
    log.info("Remote calls on first run (no cache): {}", remoteCallsFirstRun);
    
    // Second run with content now cached from first run
    runPerformanceTest(concurrency, uniquePaths, true, true, true);
    long remoteCallsSecondRun = totalRemoteCalls.sum();
    log.info("Remote calls on second run (with cache): {}", remoteCallsSecondRun);
    
    // Cached content should eliminate most remote calls
    assertThat("Caching should eliminate most remote calls",
        remoteCallsSecondRun, lessThan(remoteCallsFirstRun / 10)); // At least 90% reduction
  }
  
  /**
   * Tests error handling with virtual threads.
   */
  @Test
  public void testErrorHandling() throws Exception {
    // Configure cooperation
    underTest.configureCooperation(
        cooperationFactory,
        cooperationFactory,
        false,
        false,
        true,
        Duration.ofSeconds(60),
        Duration.ofSeconds(10),
        MEDIUM_CONCURRENCY
    );
    underTest.buildCooperation();
    
    // Clear state
    storage.clear();
    remoteCallCounters.clear();
    totalRemoteCalls.reset();
    
    // Create a mix of normal and error requests
    List<Request> requests = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      requests.add(request("normal-path-" + i));
      requests.add(request("error-path-" + i));
    }
    
    // Execute with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executeRequests(requests, executor);
    }
    
    // Verify that error paths were called exactly once each
    for (int i = 0; i < 100; i++) {
      String errorPath = "error-path-" + i;
      AtomicInteger counter = remoteCallCounters.get(errorPath);
      assertThat("Error path should be called exactly once",
          counter.get(), is(1));
    }
    
    // Verify that normal paths were also called
    for (int i = 0; i < 100; i++) {
      String normalPath = "normal-path-" + i;
      AtomicInteger counter = remoteCallCounters.get(normalPath);
      assertThat("Normal path should be called",
          counter.get(), is(1));
    }
  }
  
  /**
   * Tests throttling behavior with virtual threads.
   */
  @Test
  public void testThrottling() throws Exception {
    int concurrency = MEDIUM_CONCURRENCY;
    int uniquePaths = 100;
    int maxConcurrentRemoteCalls = 10; // Limit concurrent remote calls
    
    // Configure cooperation
    underTest.configureCooperation(
        cooperationFactory,
        cooperationFactory,
        false,
        false,
        true,
        Duration.ofSeconds(60),
        Duration.ofSeconds(10),
        concurrency
    );
    underTest.buildCooperation();
    
    // Clear state
    storage.clear();
    remoteCallCounters.clear();
    totalRemoteCalls.reset();
    
    // Limit concurrent remote calls
    remoteCallPermits = new Semaphore(maxConcurrentRemoteCalls);
    
    // Generate requests
    List<Request> requests = generateRandomRequests("throttled-path-", concurrency, uniquePaths);
    
    // Track max concurrent remote calls
    AtomicInteger currentCalls = new AtomicInteger(0);
    AtomicInteger maxCalls = new AtomicInteger(0);
    
    // Create a thread to monitor concurrency
    Thread monitor = new Thread(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          int current = concurrency - remoteCallPermits.availablePermits();
          currentCalls.set(current);
          maxCalls.updateAndGet(max -> Math.max(max, current));
          Thread.sleep(10);
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    monitor.setDaemon(true);
    monitor.start();
    
    try {
      // Execute with virtual threads
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executeRequests(requests, executor);
      }
      
      // Verify throttling worked
      log.info("Maximum concurrent remote calls: {}", maxCalls.get());
      assertThat("Remote calls should be throttled",
          maxCalls.get(), lessThan(maxConcurrentRemoteCalls + 1)); // +1 for potential race condition
    }
    finally {
      monitor.interrupt();
    }
  }
  
  /**
   * Compares memory usage between platform threads and virtual threads.
   */
  @Test
  public void testMemoryUsage() throws Exception {
    // This test is more of a demonstration than a strict assertion,
    // as memory usage can vary significantly between environments
    
    // Function to measure memory usage
    Supplier<Long> getMemoryUsage = () -> {
      System.gc(); // Request garbage collection to get more accurate readings
      return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    };
    
    int concurrency = MEDIUM_CONCURRENCY;
    int uniquePaths = 100;
    
    // Measure baseline memory usage
    long baselineMemory = getMemoryUsage.get();
    log.info("Baseline memory usage: {} MB", baselineMemory / (1024 * 1024));
    
    // Measure with platform threads
    ExecutorService platformExecutor = createExecutor(concurrency, false);
    long platformMemoryBefore = getMemoryUsage.get();
    platformExecutor.shutdown();
    platformExecutor.awaitTermination(1, SECONDS);
    long platformMemoryAfter = getMemoryUsage.get();
    long platformMemoryUsage = platformMemoryAfter - platformMemoryBefore;
    log.info("Platform threads memory usage: {} MB", platformMemoryUsage / (1024 * 1024));
    
    // Measure with virtual threads
    ExecutorService virtualExecutor = createExecutor(concurrency, true);
    long virtualMemoryBefore = getMemoryUsage.get();
    virtualExecutor.shutdown();
    virtualExecutor.awaitTermination(1, SECONDS);
    long virtualMemoryAfter = getMemoryUsage.get();
    long virtualMemoryUsage = virtualMemoryAfter - virtualMemoryBefore;
    log.info("Virtual threads memory usage: {} MB", virtualMemoryUsage / (1024 * 1024));
    
    // Log memory usage ratio
    if (virtualMemoryUsage > 0) { // Avoid division by zero
      log.info("Memory usage ratio (platform/virtual): {}", (double) platformMemoryUsage / virtualMemoryUsage);
    }
  }
}