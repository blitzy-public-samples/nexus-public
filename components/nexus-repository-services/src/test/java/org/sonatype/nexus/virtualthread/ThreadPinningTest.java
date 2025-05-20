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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Tests to detect and analyze thread pinning issues when using Virtual Threads with Nexus Repository components.
 * <p>
 * Thread pinning occurs when a virtual thread becomes temporarily bound to its carrier thread, limiting scalability.
 * This test class identifies operations that cause pinning, such as synchronized blocks, native methods, or certain
 * I/O operations, and verifies that core components like ProxyFacetSupport avoid these patterns.
 * <p>
 * Without these tests, thread pinning issues might go undetected, severely limiting the performance benefits of
 * virtual threads.
 */
public class ThreadPinningTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int PINNING_THRESHOLD_MS = 20; // JFR default threshold for pinning events
  private static final String TEST_URL = "https://repo1.maven.org/maven2/org/apache/maven/maven-core/3.9.6/maven-core-3.9.6.pom";

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

  // Statistics for pinning detection
  private final AtomicInteger pinnedThreadCount = new AtomicInteger(0);
  private final Map<String, Long> pinnedThreadDurations = new ConcurrentHashMap<>();
  private final Map<Thread, Long> threadStartTimes = new ConcurrentHashMap<>();

  @Spy
  ProxyFacetSupport proxyFacet = new ProxyFacetSupport()
  {
    @Nullable
    @Override
    protected Content getCachedContent(final Context context) {
      return null; // Always fetch from remote for testing purposes
    }

    @Override
    protected Content store(final Context context, final Content content) {
      return content;
    }

    @Override
    protected void indicateVerified(final Context context, final Content content, final CacheInfo cacheInfo) {
      // no-op
    }

    @Override
    protected String getUrl(@Nonnull final Context context) {
      return TEST_URL;
    }

    @Override
    protected Content fetch(final String url, final Context context, final Content stale) throws IOException {
      try {
        // Use HttpClient which supports virtual threads well
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();
        
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        
        // Simulate some processing time
        Thread.sleep(50);
        
        return content;
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted during fetch", e);
      }
    }
  };

  @Before
  public void setUp() throws Exception {
    when(attributesMap.get(CacheInfo.class)).thenReturn(cacheInfo);
    when(content.getAttributes()).thenReturn(attributesMap);
    when(cacheController.isStale(cacheInfo)).thenReturn(false);
    when(cacheControllerHolder.getContentCacheController()).thenReturn(cacheController);
    when(repository.getName()).thenReturn("test-repo");
    when(format.getValue()).thenReturn("raw");
    when(repository.getFormat()).thenReturn(format);

    proxyFacet.installDependencies(eventManager);
    proxyFacet.cacheControllerHolder = cacheControllerHolder;
    proxyFacet.attach(repository);
  }

  /**
   * Creates a request for testing.
   */
  private Request createRequest(final String path) {
    return new Request.Builder().action(GET).path(path).build();
  }

  /**
   * Detects if the current thread is pinned by measuring execution time of a blocking operation.
   * If the operation takes longer than expected, it may indicate thread pinning.
   *
   * @param operationName Name of the operation being tested
   * @param runnable The operation to test for pinning
   */
  private void detectPinning(String operationName, Runnable runnable) {
    Thread currentThread = Thread.currentThread();
    threadStartTimes.put(currentThread, System.currentTimeMillis());
    
    try {
      runnable.run();
    }
    finally {
      long startTime = threadStartTimes.remove(currentThread);
      long duration = System.currentTimeMillis() - startTime;
      
      if (duration > PINNING_THRESHOLD_MS) {
        pinnedThreadCount.incrementAndGet();
        pinnedThreadDurations.put(operationName + "-" + currentThread.getName(), duration);
        log.warn("Potential thread pinning detected in {} - operation took {} ms", 
            operationName, duration);
      }
    }
  }

  /**
   * Tests if ProxyFacetSupport operations cause thread pinning when using virtual threads.
   * This test creates multiple virtual threads that concurrently access a proxy repository.
   */
  @Test
  public void testProxyFacetWithVirtualThreads() throws Exception {
    // Skip test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping virtual thread test as it requires Java 21 or later");
      return;
    }

    int threadCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final String path = "test/path-" + i;
        executor.submit(() -> {
          try {
            Request request = createRequest(path);
            Context context = new Context(repository, request);
            
            detectPinning("proxyFacet.get", () -> {
              try {
                proxyFacet.get(context);
                successCount.incrementAndGet();
              }
              catch (IOException e) {
                log.error("Error in proxy facet get", e);
                failureCount.incrementAndGet();
              }
            });
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(30, SECONDS);
      assertThat("All tasks should complete in time", completed, is(true));
      
      // Verify results
      assertThat("All requests should succeed", successCount.get(), is(threadCount));
      assertThat("No requests should fail", failureCount.get(), is(0));
      
      // Check for pinning - in an optimized implementation, we should see minimal pinning
      assertThat("Thread pinning should be minimal", pinnedThreadCount.get(), lessThan(threadCount / 10));
    }
  }

  /**
   * Demonstrates a scenario that causes thread pinning using synchronized blocks.
   * This test shows how synchronized blocks can cause virtual threads to be pinned to carrier threads.
   */
  @Test
  public void testSynchronizedBlockPinning() throws Exception {
    // Skip test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping virtual thread test as it requires Java 21 or later");
      return;
    }

    int threadCount = 10; // Smaller count for this test
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger pinnedCount = new AtomicInteger(0);
    Object lock = new Object();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            long startTime = System.currentTimeMillis();
            
            // This synchronized block will cause pinning when the thread sleeps
            synchronized (lock) {
              // Simulate I/O or blocking operation inside synchronized block
              // This will cause the virtual thread to be pinned to its carrier thread
              Thread.sleep(100);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            if (duration >= PINNING_THRESHOLD_MS) {
              pinnedCount.incrementAndGet();
              log.info("Thread pinned for {} ms in synchronized block", duration);
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(5, SECONDS);
      assertThat("All tasks should complete in time", completed, is(true));
      
      // All threads should be pinned in this test case
      assertThat("All threads should be pinned", pinnedCount.get(), is(threadCount));
    }
  }

  /**
   * Demonstrates a non-pinning alternative using ReentrantLock instead of synchronized blocks.
   * This test shows how ReentrantLock can be used to avoid thread pinning.
   */
  @Test
  public void testReentrantLockNonPinning() throws Exception {
    // Skip test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping virtual thread test as it requires Java 21 or later");
      return;
    }

    int threadCount = 10; // Smaller count for this test
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger pinnedCount = new AtomicInteger(0);
    ReentrantLock lock = new ReentrantLock();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            long startTime = System.currentTimeMillis();
            
            // Using ReentrantLock instead of synchronized block
            lock.lock();
            try {
              // Simulate I/O or blocking operation
              // This should NOT cause the virtual thread to be pinned
              Thread.sleep(100);
            }
            finally {
              lock.unlock();
            }
            
            long duration = System.currentTimeMillis() - startTime;
            if (duration >= PINNING_THRESHOLD_MS) {
              pinnedCount.incrementAndGet();
              log.info("Thread pinned for {} ms with ReentrantLock", duration);
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(5, SECONDS);
      assertThat("All tasks should complete in time", completed, is(true));
      
      // Few or no threads should be pinned in this test case
      assertThat("Few or no threads should be pinned", pinnedCount.get(), lessThan(threadCount / 2));
    }
  }

  /**
   * Tests carrier thread utilization under load to detect pinning issues.
   * This test measures how efficiently carrier threads are utilized when many virtual threads are active.
   */
  @Test
  public void testCarrierThreadUtilization() throws Exception {
    // Skip test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping virtual thread test as it requires Java 21 or later");
      return;
    }

    int threadCount = 1000; // Large number of virtual threads
    CountDownLatch latch = new CountDownLatch(threadCount);
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    
    // Track active threads to measure carrier thread utilization
    AtomicInteger activeThreads = new AtomicInteger(0);
    AtomicInteger maxActiveThreads = new AtomicInteger(0);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Increment active count and update max if needed
            int active = activeThreads.incrementAndGet();
            updateMax(maxActiveThreads, active);
            
            // Simulate I/O-bound work that should not pin threads
            Thread.sleep(50 + (long) (Math.random() * 50));
            
            // Decrement active count
            activeThreads.decrementAndGet();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(10, SECONDS);
      assertThat("All tasks should complete in time", completed, is(true));
      
      // In an efficient implementation, the max active threads should be close to the number of processors
      // If there's significant pinning, it will be much higher
      log.info("Max active threads: {}, Available processors: {}", maxActiveThreads.get(), availableProcessors);
      
      // Allow some overhead, but should be reasonably close to processor count if no pinning occurs
      assertThat("Max active threads should be reasonably close to processor count", 
          maxActiveThreads.get(), lessThan(availableProcessors * 4));
    }
  }

  /**
   * Measures the impact of thread pinning on throughput.
   * This test compares the throughput of operations with and without thread pinning.
   */
  @Test
  public void testPinningImpactOnThroughput() throws Exception {
    // Skip test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping virtual thread test as it requires Java 21 or later");
      return;
    }

    int operationsPerTest = 100;
    int testDurationMs = 2000;
    
    // Test with synchronized (pinning)
    Object lock = new Object();
    AtomicInteger syncOps = new AtomicInteger(0);
    long syncStartTime = System.currentTimeMillis();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < operationsPerTest; i++) {
        executor.submit(() -> {
          try {
            synchronized (lock) {
              // Simulate I/O operation that will cause pinning
              Thread.sleep(50);
              syncOps.incrementAndGet();
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      
      // Wait for test duration
      Thread.sleep(testDurationMs);
    }
    
    long syncEndTime = System.currentTimeMillis();
    double syncThroughput = syncOps.get() / ((syncEndTime - syncStartTime) / 1000.0);
    
    // Test with ReentrantLock (non-pinning)
    ReentrantLock reentrantLock = new ReentrantLock();
    AtomicInteger lockOps = new AtomicInteger(0);
    long lockStartTime = System.currentTimeMillis();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < operationsPerTest; i++) {
        executor.submit(() -> {
          try {
            reentrantLock.lock();
            try {
              // Same operation but without pinning
              Thread.sleep(50);
              lockOps.incrementAndGet();
            }
            finally {
              reentrantLock.unlock();
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      
      // Wait for test duration
      Thread.sleep(testDurationMs);
    }
    
    long lockEndTime = System.currentTimeMillis();
    double lockThroughput = lockOps.get() / ((lockEndTime - lockStartTime) / 1000.0);
    
    log.info("Throughput with synchronized (pinning): {} ops/sec", syncThroughput);
    log.info("Throughput with ReentrantLock (non-pinning): {} ops/sec", lockThroughput);
    
    // Non-pinning implementation should have higher throughput
    assertThat("Non-pinning implementation should have higher throughput", 
        lockThroughput, greaterThanOrEqualTo(syncThroughput));
  }

  /**
   * Helper method to update the maximum value in an AtomicInteger.
   */
  private void updateMax(AtomicInteger maxValue, int newValue) {
    int current;
    do {
      current = maxValue.get();
      if (newValue <= current) {
        break;
      }
    } while (!maxValue.compareAndSet(current, newValue));
  }

  /**
   * Checks if the current Java version is 21 or later.
   */
  private boolean isJava21OrLater() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      // Old version format: 1.8.x
      return false;
    }
    else {
      // New version format: 11.x, 17.x, 21.x
      int majorVersion = Integer.parseInt(version.split("\\.")[0]);
      return majorVersion >= 21;
    }
  }
}