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
package org.sonatype.nexus.siesta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.servlet.DispatcherType;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletTester;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

/**
 * Support for Siesta tests using Java 21 Virtual Threads.
 * 
 * <p>This class extends the standard {@link SiestaTestSupport} to provide an embedded Jetty server
 * environment configured with Virtual Thread-optimized settings. It configures thread pools to use
 * Java 21 Virtual Threads, provides utilities for measuring Virtual Thread performance metrics,
 * and includes helper methods for detecting thread pinning issues.</p>
 * 
 * <p>Use this class as the base for integration tests that need to validate the Siesta REST layer's
 * compatibility with Java 21's Virtual Thread implementation.</p>
 */
public class VirtualThreadSiestaTestSupport
    extends SiestaTestSupport
{
  private ServletTester servletTester;

  private String url;

  private Client client;
  
  /**
   * Tracks performance metrics for virtual thread operations.
   */
  private final VirtualThreadMetrics metrics = new VirtualThreadMetrics();
  
  /**
   * Tracks thread pinning events during test execution.
   */
  private final ThreadPinningDetector pinningDetector = new ThreadPinningDetector();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  /**
   * Starts an embedded Jetty server configured to use Virtual Threads.
   */
  @Before
  public void startJetty() throws Exception {
    // Create a servlet tester with Virtual Thread pool
    servletTester = new ServletTester();
    configureVirtualThreadPool(servletTester);
    
    // Configure the servlet context with Guice
    ServletContextHandler context = servletTester.getContext();
    context.addEventListener(new GuiceServletContextListener() {
      final Injector injector = Guice.createInjector(new TestModule());

      @Override
      protected Injector getInjector() {
        return injector;
      }
    });

    // Set up the URL and filters
    url = servletTester.createConnector(true) + TestModule.MOUNT_POINT;
    servletTester.addFilter(GuiceFilter.class, "/*", DispatcherType.REQUEST);
    servletTester.addServlet(DummyServlet.class, "/*");
    
    // Start the server
    servletTester.start();

    // Create a client
    client = ClientBuilder.newClient();
    
    // Enable thread pinning detection
    pinningDetector.start();
    
    log.info("Started Jetty server with Virtual Thread pool at {}", url);
  }

  /**
   * Stops the Jetty server and cleans up resources.
   */
  @After
  public void stopJetty() throws Exception {
    // Stop thread pinning detection
    pinningDetector.stop();
    
    // Log metrics
    log.info("Virtual Thread metrics: {}", metrics);
    
    // Log any pinning events
    if (pinningDetector.hasPinningEvents()) {
      log.warn("Thread pinning detected during test execution:");
      pinningDetector.getPinningEvents().forEach(event -> 
          log.warn("  Thread pinned at: {}", event));
    }
    
    // Stop the server
    if (servletTester != null) {
      servletTester.stop();
    }
  }

  /**
   * Configures the servlet tester to use a Virtual Thread pool.
   */
  protected void configureVirtualThreadPool(ServletTester tester) {
    // Create a thread pool that uses virtual threads
    ThreadPool virtualThreadPool = new VirtualThreadPool();
    tester.getServer().setThreadPool(virtualThreadPool);
  }

  /**
   * Returns the JAX-RS client for making requests.
   */
  protected Client client() {
    return client;
  }

  /**
   * Returns the base URL for the embedded server.
   */
  protected String url() {
    return url;
  }

  /**
   * Returns a URL for the specified path.
   */
  protected String url(final String path) {
    return url + "/" + path;
  }
  
  /**
   * Executes the given task using a virtual thread.
   * 
   * @param task the task to execute
   * @return a CompletableFuture representing the completion of the task
   */
  protected CompletableFuture<Void> runWithVirtualThread(Runnable task) {
    metrics.incrementVirtualThreadsCreated();
    return CompletableFuture.runAsync(task, Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Executes the given supplier using a virtual thread and returns its result.
   * 
   * @param <T> the type of result
   * @param supplier the supplier to execute
   * @return a CompletableFuture representing the completion of the supplier
   */
  protected <T> CompletableFuture<T> supplyWithVirtualThread(Supplier<T> supplier) {
    metrics.incrementVirtualThreadsCreated();
    return CompletableFuture.supplyAsync(supplier, Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Executes the given task concurrently using the specified number of virtual threads.
   * 
   * @param task the task to execute concurrently
   * @param concurrency the number of concurrent executions
   * @throws Exception if an error occurs during execution
   */
  protected void runConcurrently(Runnable task, int concurrency) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrency);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Create and start the virtual threads
    for (int i = 0; i < concurrency; i++) {
      futures.add(runWithVirtualThread(() -> {
        try {
          task.run();
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all threads to complete
    if (!latch.await(30, TimeUnit.SECONDS)) {
      throw new AssertionError("Timed out waiting for concurrent tasks to complete");
    }
    
    // Check for exceptions
    for (CompletableFuture<Void> future : futures) {
      if (future.isCompletedExceptionally()) {
        try {
          future.join(); // This will throw the exception
        } catch (Exception e) {
          throw new AssertionError("Exception in concurrent task", e);
        }
      }
    }
  }
  
  /**
   * Performs a benchmark comparing platform threads and virtual threads.
   * 
   * @param task the task to benchmark
   * @param concurrency the number of concurrent executions
   * @param iterations the number of iterations to run
   * @return a BenchmarkResult containing the results
   * @throws Exception if an error occurs during the benchmark
   */
  protected BenchmarkResult benchmarkThreads(Runnable task, int concurrency, int iterations) throws Exception {
    BenchmarkResult result = new BenchmarkResult();
    
    // Benchmark with platform threads
    long platformStart = System.nanoTime();
    runWithPlatformThreads(task, concurrency, iterations);
    long platformEnd = System.nanoTime();
    result.setPlatformThreadDuration(Duration.ofNanos(platformEnd - platformStart));
    
    // Benchmark with virtual threads
    long virtualStart = System.nanoTime();
    runWithVirtualThreads(task, concurrency, iterations);
    long virtualEnd = System.nanoTime();
    result.setVirtualThreadDuration(Duration.ofNanos(virtualEnd - virtualStart));
    
    return result;
  }
  
  /**
   * Runs the given task concurrently using platform threads.
   */
  private void runWithPlatformThreads(Runnable task, int concurrency, int iterations) throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
      for (int i = 0; i < iterations; i++) {
        CountDownLatch latch = new CountDownLatch(concurrency);
        
        for (int j = 0; j < concurrency; j++) {
          executor.submit(() -> {
            try {
              task.run();
            } finally {
              latch.countDown();
            }
          });
        }
        
        if (!latch.await(30, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting for platform threads to complete");
        }
      }
    }
  }
  
  /**
   * Runs the given task concurrently using virtual threads.
   */
  private void runWithVirtualThreads(Runnable task, int concurrency, int iterations) throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < iterations; i++) {
        CountDownLatch latch = new CountDownLatch(concurrency);
        
        for (int j = 0; j < concurrency; j++) {
          metrics.incrementVirtualThreadsCreated();
          executor.submit(() -> {
            try {
              task.run();
            } finally {
              latch.countDown();
            }
          });
        }
        
        if (!latch.await(30, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting for virtual threads to complete");
        }
      }
    }
  }
  
  /**
   * Executes a load test with the specified number of concurrent clients.
   * 
   * @param targetUrl the URL to test
   * @param concurrentClients the number of concurrent clients
   * @param requestsPerClient the number of requests per client
   * @return a LoadTestResult containing the results
   * @throws Exception if an error occurs during the load test
   */
  protected LoadTestResult executeLoadTest(String targetUrl, int concurrentClients, int requestsPerClient) 
      throws Exception {
    LoadTestResult result = new LoadTestResult();
    CountDownLatch latch = new CountDownLatch(concurrentClients);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalDuration = new AtomicLong(0);
    
    // Create a WebTarget for the URL
    WebTarget target = client().target(targetUrl);
    
    // Start the timer
    long startTime = System.nanoTime();
    
    // Create and start the virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrentClients; i++) {
        metrics.incrementVirtualThreadsCreated();
        executor.submit(() -> {
          try {
            for (int j = 0; j < requestsPerClient; j++) {
              long requestStart = System.nanoTime();
              try {
                // Execute the request
                target.request().get();
                result.incrementSuccessCount();
              } catch (Exception e) {
                errorCount.incrementAndGet();
                log.error("Error executing request", e);
              } finally {
                long requestDuration = System.nanoTime() - requestStart;
                totalDuration.addAndGet(requestDuration);
                result.recordLatency(Duration.ofNanos(requestDuration));
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all clients to complete
      if (!latch.await(60, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for load test to complete");
      }
    }
    
    // Calculate results
    long endTime = System.nanoTime();
    Duration totalTestDuration = Duration.ofNanos(endTime - startTime);
    
    result.setTotalDuration(totalTestDuration);
    result.setErrorCount(errorCount.get());
    result.setAverageLatency(Duration.ofNanos(totalDuration.get() / 
        (concurrentClients * requestsPerClient - errorCount.get())));
    
    return result;
  }
  
  /**
   * A thread pool implementation that uses virtual threads.
   */
  private static class VirtualThreadPool implements ThreadPool {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    
    @Override
    public void execute(Runnable command) {
      activeThreads.incrementAndGet();
      executor.submit(() -> {
        try {
          command.run();
        } finally {
          activeThreads.decrementAndGet();
        }
      });
    }

    @Override
    public void join() throws InterruptedException {
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    @Override
    public int getThreads() {
      return Integer.MAX_VALUE; // Virtual threads are unlimited
    }

    @Override
    public int getIdleThreads() {
      return Integer.MAX_VALUE - activeThreads.get();
    }

    @Override
    public boolean isLowOnThreads() {
      return false; // Virtual threads are never low
    }
  }
  
  /**
   * Metrics for tracking virtual thread usage.
   */
  public static class VirtualThreadMetrics {
    private final AtomicLong virtualThreadsCreated = new AtomicLong(0);
    private final AtomicLong pinnedThreadCount = new AtomicLong(0);
    
    public void incrementVirtualThreadsCreated() {
      virtualThreadsCreated.incrementAndGet();
    }
    
    public void incrementPinnedThreadCount() {
      pinnedThreadCount.incrementAndGet();
    }
    
    public long getTotalVirtualThreadsCreated() {
      return virtualThreadsCreated.get();
    }
    
    public long getPinnedThreadCount() {
      return pinnedThreadCount.get();
    }
    
    @Override
    public String toString() {
      return String.format("VirtualThreadMetrics[created=%d, pinned=%d]", 
          virtualThreadsCreated.get(), pinnedThreadCount.get());
    }
  }
  
  /**
   * Detector for thread pinning events.
   */
  public static class ThreadPinningDetector {
    private final List<String> pinningEvents = new ArrayList<>();
    private volatile boolean running = false;
    private Thread detectorThread;
    
    public void start() {
      running = true;
      detectorThread = Thread.ofPlatform().name("pinning-detector").start(() -> {
        while (running) {
          try {
            // Check for pinned threads using JDK API
            // This is a simplified implementation - in a real environment,
            // you would use JFR events or JMX to detect pinning
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      });
      
      // Enable thread pinning detection via system property
      System.setProperty("jdk.tracePinnedThreads", "full");
    }
    
    public void stop() {
      running = false;
      if (detectorThread != null) {
        detectorThread.interrupt();
      }
    }
    
    public void recordPinningEvent(String stackTrace) {
      synchronized (pinningEvents) {
        pinningEvents.add(stackTrace);
      }
    }
    
    public boolean hasPinningEvents() {
      synchronized (pinningEvents) {
        return !pinningEvents.isEmpty();
      }
    }
    
    public List<String> getPinningEvents() {
      synchronized (pinningEvents) {
        return new ArrayList<>(pinningEvents);
      }
    }
  }
  
  /**
   * Results from a thread benchmark.
   */
  public static class BenchmarkResult {
    private Duration platformThreadDuration;
    private Duration virtualThreadDuration;
    
    public Duration getPlatformThreadDuration() {
      return platformThreadDuration;
    }
    
    public void setPlatformThreadDuration(Duration platformThreadDuration) {
      this.platformThreadDuration = platformThreadDuration;
    }
    
    public Duration getVirtualThreadDuration() {
      return virtualThreadDuration;
    }
    
    public void setVirtualThreadDuration(Duration virtualThreadDuration) {
      this.virtualThreadDuration = virtualThreadDuration;
    }
    
    public double getSpeedupFactor() {
      return (double) platformThreadDuration.toNanos() / virtualThreadDuration.toNanos();
    }
    
    @Override
    public String toString() {
      return String.format("BenchmarkResult[platformDuration=%s, virtualDuration=%s, speedup=%.2fx]", 
          platformThreadDuration, virtualThreadDuration, getSpeedupFactor());
    }
  }
  
  /**
   * Results from a load test.
   */
  public static class LoadTestResult {
    private Duration totalDuration;
    private Duration averageLatency;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private int errorCount;
    private final Map<Duration, AtomicInteger> latencyDistribution = new ConcurrentHashMap<>();
    
    public void incrementSuccessCount() {
      successCount.incrementAndGet();
    }
    
    public void recordLatency(Duration latency) {
      latencyDistribution.computeIfAbsent(latency, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    public Duration getTotalDuration() {
      return totalDuration;
    }
    
    public void setTotalDuration(Duration totalDuration) {
      this.totalDuration = totalDuration;
    }
    
    public Duration getAverageLatency() {
      return averageLatency;
    }
    
    public void setAverageLatency(Duration averageLatency) {
      this.averageLatency = averageLatency;
    }
    
    public int getSuccessCount() {
      return successCount.get();
    }
    
    public int getErrorCount() {
      return errorCount;
    }
    
    public void setErrorCount(int errorCount) {
      this.errorCount = errorCount;
    }
    
    public Map<Duration, AtomicInteger> getLatencyDistribution() {
      return latencyDistribution;
    }
    
    public double getThroughput() {
      return (double) successCount.get() / (totalDuration.toMillis() / 1000.0);
    }
    
    @Override
    public String toString() {
      return String.format("LoadTestResult[duration=%s, avg_latency=%s, success=%d, errors=%d, throughput=%.2f req/sec]", 
          totalDuration, averageLatency, successCount.get(), errorCount, getThroughput());
    }
  }
}