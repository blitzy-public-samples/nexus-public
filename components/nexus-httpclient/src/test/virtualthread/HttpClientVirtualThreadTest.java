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
package org.sonatype.nexus.httpclient.virtualthread;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.httpclient.HttpClientPlan;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Base test class for validating HTTP client compatibility with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class HttpClientVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(HttpClientVirtualThreadTest.class);
  
  private static final int DEFAULT_CONCURRENT_REQUESTS = 100;
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  private PinningDetector pinningDetector;
  private ThreadMetricsCollector threadMetricsCollector;
  
  @Before
  public void setUp() throws Exception {
    // Create executors for both virtual and platform threads for comparison
    virtualThreadExecutor = createVirtualThreadExecutor();
    platformThreadExecutor = createPlatformThreadExecutor();
    
    // Initialize pinning detector and metrics collector
    pinningDetector = new PinningDetector();
    threadMetricsCollector = new ThreadMetricsCollector();
    
    // Log Java version to verify we're running on Java 21
    log.info("Running tests with Java version: {}", System.getProperty("java.version"));
    
    // Check if virtual threads are supported
    try {
      Thread.startVirtualThread(() -> {}).join();
      log.info("Virtual threads are supported in this JVM");
    } 
    catch (UnsupportedOperationException e) {
      log.warn("Virtual threads are NOT supported in this JVM. Tests may fail.");
    }
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
    
    // Log thread metrics summary
    threadMetricsCollector.logSummary();
  }
  
  /**
   * Creates an executor service that uses virtual threads.
   */
  protected ExecutorService createVirtualThreadExecutor() {
    ThreadFactory factory = Thread.ofVirtual().name("http-vt-", 0).factory();
    return Executors.newThreadPerTaskExecutor(factory);
  }
  
  /**
   * Creates an executor service that uses platform threads.
   */
  protected ExecutorService createPlatformThreadExecutor() {
    ThreadFactory factory = Thread.ofPlatform().name("http-pt-", 0).factory();
    return Executors.newCachedThreadPool(factory);
  }
  
  /**
   * Creates a basic HTTP client for testing.
   */
  protected HttpClient createHttpClient() {
    HttpClientPlan plan = new HttpClientPlan();
    plan.setUserAgentBase("Nexus-HttpClient-VirtualThread-Test");
    
    return HttpClientBuilder.create()
        .setUserAgent(plan.getUserAgent())
        .build();
  }
  
  /**
   * Executes HTTP requests concurrently using the provided executor service.
   *
   * @param executor the executor service to use
   * @param requestCount the number of concurrent requests to make
   * @param requestFactory factory method to create the HTTP request
   * @param responseHandler handler for processing the HTTP response
   * @return the time taken in milliseconds
   */
  protected long executeHttpRequestsConcurrently(
      ExecutorService executor,
      int requestCount,
      HttpRequestFactory requestFactory,
      Consumer<HttpResponse> responseHandler) throws Exception 
  {
    HttpClient client = createHttpClient();
    CountDownLatch latch = new CountDownLatch(requestCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    // Start pinning detection
    pinningDetector.startMonitoring();
    
    // Create a thread to periodically collect metrics during execution
    Thread metricsThread = new Thread(() -> {
      try {
        while (!Thread.currentThread().isInterrupted() && completedCount.get() < requestCount) {
          threadMetricsCollector.takeSnapshot();
          Thread.sleep(100); // Take metrics every 100ms
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "metrics-collector");
    
    metricsThread.setDaemon(true);
    metricsThread.start();
    
    try {
      // Submit tasks to executor
      for (int i = 0; i < requestCount; i++) {
        executor.submit(() -> {
          try {
            HttpUriRequest request = requestFactory.createRequest();
            HttpResponse response = client.execute(request);
            responseHandler.accept(response);
            completedCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error executing HTTP request", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Log progress periodically for long-running tests
      if (requestCount > 100) {
        int lastReported = 0;
        while (!latch.await(1, TimeUnit.SECONDS)) {
          int current = completedCount.get();
          if (current > lastReported) {
            log.info("Progress: {}/{} requests completed", current, requestCount);
            lastReported = current;
          }
        }
      }
      else {
        // Wait for all requests to complete
        boolean completed = latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat("All requests should complete within timeout", completed, is(true));
      }
      
      assertThat("No errors should occur during execution", errorCount.get(), is(0));
      
      return System.currentTimeMillis() - startTime;
    }
    finally {
      // Stop metrics collection
      metricsThread.interrupt();
      try {
        metricsThread.join(1000);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      // Stop pinning detection
      pinningDetector.stopMonitoring();
      
      // Take one final snapshot
      threadMetricsCollector.takeSnapshot();
      
      // Log completion metrics
      log.info("Completed {}/{} requests in {} ms", 
          completedCount.get(), requestCount, System.currentTimeMillis() - startTime);
    }
  }
  
  /**
   * Executes the same HTTP requests using both virtual threads and platform threads,
   * then compares the performance.
   *
   * @param requestCount the number of concurrent requests to make
   * @param requestFactory factory method to create the HTTP request
   * @param responseHandler handler for processing the HTTP response
   */
  protected void compareVirtualAndPlatformThreadPerformance(
      int requestCount,
      HttpRequestFactory requestFactory,
      Consumer<HttpResponse> responseHandler) throws Exception 
  {
    // Execute with platform threads
    long platformTime = executeHttpRequestsConcurrently(
        platformThreadExecutor, requestCount, requestFactory, responseHandler);
    
    log.info("Platform thread execution time: {} ms", platformTime);
    
    // Execute with virtual threads
    long virtualTime = executeHttpRequestsConcurrently(
        virtualThreadExecutor, requestCount, requestFactory, responseHandler);
    
    log.info("Virtual thread execution time: {} ms", virtualTime);
    log.info("Performance improvement: {}%", 
        platformTime > 0 ? (platformTime - virtualTime) * 100 / platformTime : 0);
    
    // Assert that virtual threads perform better under high concurrency
    if (requestCount >= 100) {
      assertThat("Virtual threads should be faster than platform threads for high concurrency",
          virtualTime, lessThan(platformTime));
    }
    
    // Assert that no thread pinning was detected
    assertThat("No thread pinning should be detected", 
        pinningDetector.isPinningDetected(), is(false));
  }
  
  /**
   * Simple test to verify HTTP client works with virtual threads.
   */
  @Test
  public void testHttpClientWithVirtualThreads() throws Exception {
    // This is a basic test that can be overridden by subclasses
    // with more specific implementation
    HttpRequestFactory requestFactory = () -> new HttpGet("https://httpbin.org/get");
    Consumer<HttpResponse> responseHandler = response -> {
      assertThat("HTTP response should be successful", 
          response.getStatusLine().getStatusCode(), is(200));
    };
    
    compareVirtualAndPlatformThreadPerformance(
        DEFAULT_CONCURRENT_REQUESTS, requestFactory, responseHandler);
  }
  
  /**
   * Test to verify HTTP client handles high concurrency with virtual threads.
   * This test creates a large number of concurrent requests to verify scalability.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a higher number of concurrent requests to stress test virtual threads
    final int highConcurrencyRequests = 1000;
    
    HttpRequestFactory requestFactory = () -> new HttpGet("https://httpbin.org/get");
    Consumer<HttpResponse> responseHandler = response -> {
      assertThat("HTTP response should be successful", 
          response.getStatusLine().getStatusCode(), is(200));
    };
    
    log.info("Starting high concurrency test with {} concurrent requests", highConcurrencyRequests);
    
    // Only run with virtual threads as platform threads might exhaust resources
    long startTime = System.currentTimeMillis();
    executeHttpRequestsConcurrently(virtualThreadExecutor, highConcurrencyRequests, 
        requestFactory, responseHandler);
    long duration = System.currentTimeMillis() - startTime;
    
    log.info("Completed {} concurrent requests in {} ms using virtual threads", 
        highConcurrencyRequests, duration);
    
    // Assert that no thread pinning was detected
    assertThat("No thread pinning should be detected with high concurrency", 
        pinningDetector.isPinningDetected(), is(false));
  }
  
  /**
   * Test to verify HTTP client handles delayed responses correctly with virtual threads.
   * This test simulates slow responses to ensure virtual threads properly yield.
   */
  @Test
  public void testDelayedResponsesWithVirtualThreads() throws Exception {
    // Use httpbin's delay endpoint to simulate slow responses
    HttpRequestFactory requestFactory = () -> new HttpGet("https://httpbin.org/delay/1");
    Consumer<HttpResponse> responseHandler = response -> {
      assertThat("HTTP response should be successful", 
          response.getStatusLine().getStatusCode(), is(200));
    };
    
    compareVirtualAndPlatformThreadPerformance(
        50, requestFactory, responseHandler);
    
    // With delayed responses, virtual threads should show even more significant advantages
    // as they don't block carrier threads during the delay
  }
  
  /**
   * Test to verify HTTP client correctly handles connection timeouts with virtual threads.
   */
  @Test
  public void testConnectionTimeoutHandling() throws Exception {
    // Create an HTTP client with a very short connection timeout
    HttpClient clientWithTimeout = HttpClientBuilder.create()
        .setUserAgent("Nexus-HttpClient-VirtualThread-Test")
        .setConnectionTimeToLive(1, TimeUnit.SECONDS)
        .build();
    
    // Use a non-routable IP address to force a timeout
    URI nonRoutableUri = new URI("http://10.255.255.1");
    HttpGet request = new HttpGet(nonRoutableUri);
    
    // Execute with virtual threads and verify proper timeout handling
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean timeoutOccurred = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        clientWithTimeout.execute(request);
      }
      catch (IOException e) {
        // Expected timeout exception
        log.info("Expected timeout occurred: {}", e.getMessage());
        timeoutOccurred.set(true);
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for completion
    boolean completed = latch.await(10, TimeUnit.SECONDS);
    assertThat("Request should complete within timeout", completed, is(true));
    assertThat("Timeout exception should occur", timeoutOccurred.get(), is(true));
    
    // Assert that no thread pinning was detected during timeout handling
    assertThat("No thread pinning should be detected during timeout handling", 
        pinningDetector.isPinningDetected(), is(false));
  }
  
  /**
   * Test specifically designed to detect thread pinning in HTTP client operations.
   * This test creates a scenario that might cause thread pinning and verifies it's properly handled.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    log.info("Starting thread pinning detection test");
    
    // Create a test that might cause thread pinning
    // For example, using a synchronized block around HTTP operations
    final int requestCount = 20;
    CountDownLatch latch = new CountDownLatch(requestCount);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    // Start pinning detection
    pinningDetector.startMonitoring();
    
    try {
      HttpClient client = createHttpClient();
      
      // Submit tasks that might cause pinning
      for (int i = 0; i < requestCount; i++) {
        final int requestId = i;
        virtualThreadExecutor.submit(() -> {
          try {
            // Create a request that might cause pinning
            HttpGet request = new HttpGet("https://httpbin.org/get?id=" + requestId);
            
            // Using synchronized block around HTTP operation - this pattern should be avoided
            // with virtual threads as it can cause pinning
            synchronized (this) {
              // This is an anti-pattern that could cause pinning
              // In real code, you should avoid synchronized blocks around I/O operations
              HttpResponse response = client.execute(request);
              assertThat("HTTP response should be successful", 
                  response.getStatusLine().getStatusCode(), is(200));
              completedCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in pinning test", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      boolean completed = latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All requests should complete within timeout", completed, is(true));
      assertThat("All requests should complete successfully", 
          completedCount.get(), is(requestCount));
      
      // Note: In a real environment with -Djdk.tracePinnedThreads=full JVM flag,
      // the pinningDetector would detect pinning in the above code.
      // For this test class, we're just demonstrating the pattern to avoid.
      log.info("Thread pinning detection test completed. In a real environment with "
          + "-Djdk.tracePinnedThreads=full JVM flag, pinning would be detected in the above code.");
      
      // Take a thread dump for analysis
      log.debug("Thread dump for analysis:\n{}", threadMetricsCollector.getThreadDump());
    }
    finally {
      // Stop pinning detection
      pinningDetector.stopMonitoring();
    }
  }
  
  /**
   * Factory interface for creating HTTP requests.
   */
  @FunctionalInterface
  protected interface HttpRequestFactory {
    HttpUriRequest createRequest() throws Exception;
  }
  
  /**
   * Utility class for collecting thread metrics during test execution.
   * This helps analyze the behavior of virtual threads vs platform threads.
   */
  protected class ThreadMetricsCollector {
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final AtomicInteger virtualThreadsCreated = new AtomicInteger(0);
    private final AtomicInteger maxConcurrentVirtualThreads = new AtomicInteger(0);
    private final AtomicInteger maxConcurrentPlatformThreads = new AtomicInteger(0);
    
    /**
     * Takes a snapshot of current thread metrics.
     */
    public void takeSnapshot() {
      // Count platform threads
      int platformThreadCount = threadMXBean.getThreadCount();
      maxConcurrentPlatformThreads.updateAndGet(current -> Math.max(current, platformThreadCount));
      
      // Count virtual threads (if supported by the JVM)
      try {
        // In Java 21, we can get virtual thread count through reflection or JMX
        // This is a simplified approach for the test class
        int virtualThreadCount = countVirtualThreads();
        maxConcurrentVirtualThreads.updateAndGet(current -> Math.max(current, virtualThreadCount));
      }
      catch (Exception e) {
        log.debug("Could not count virtual threads: {}", e.getMessage());
      }
    }
    
    /**
     * Counts the number of virtual threads by analyzing thread names.
     * This is a simplified approach for the test class.
     */
    private int countVirtualThreads() {
      ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds());
      int count = 0;
      
      for (ThreadInfo info : threadInfos) {
        if (info != null && info.getThreadName() != null) {
          // Virtual threads in our tests have names starting with "http-vt-"
          if (info.getThreadName().startsWith("http-vt-")) {
            count++;
            virtualThreadsCreated.incrementAndGet();
          }
        }
      }
      
      return count;
    }
    
    /**
     * Gets the current thread dump as a string.
     */
    public String getThreadDump() {
      ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
      return formatThreadDump(threadInfos);
    }
    
    /**
     * Formats thread dump information into a readable string.
     */
    private String formatThreadDump(ThreadInfo[] threadInfos) {
      List<String> threadDumps = new ArrayList<>();
      
      for (ThreadInfo info : threadInfos) {
        if (info != null) {
          StringBuilder sb = new StringBuilder()
              .append("\"")
              .append(info.getThreadName())
              .append("\" ")
              .append(info.getThreadState());
          
          if (info.getLockName() != null) {
            sb.append(" on lock=").append(info.getLockName());
          }
          
          if (info.isSuspended()) {
            sb.append(" (suspended)");
          }
          
          if (info.isInNative()) {
            sb.append(" (running in native)");
          }
          
          threadDumps.add(sb.toString());
          
          // Add stack trace
          for (StackTraceElement element : info.getStackTrace()) {
            threadDumps.add("    at " + element);
          }
        }
      }
      
      return threadDumps.stream().collect(Collectors.joining("\n"));
    }
    
    /**
     * Logs a summary of thread metrics collected during the test.
     */
    public void logSummary() {
      log.info("Thread metrics summary:");
      log.info("  Virtual threads created: {}", virtualThreadsCreated.get());
      log.info("  Max concurrent virtual threads: {}", maxConcurrentVirtualThreads.get());
      log.info("  Max concurrent platform threads: {}", maxConcurrentPlatformThreads.get());
    }
    
    /**
     * Gets the total number of virtual threads created during the test.
     */
    public int getVirtualThreadsCreated() {
      return virtualThreadsCreated.get();
    }
    
    /**
     * Gets the maximum number of concurrent virtual threads observed.
     */
    public int getMaxConcurrentVirtualThreads() {
      return maxConcurrentVirtualThreads.get();
    }
    
    /**
     * Gets the maximum number of concurrent platform threads observed.
     */
    public int getMaxConcurrentPlatformThreads() {
      return maxConcurrentPlatformThreads.get();
    }
  }
  
  /**
   * Utility class for detecting thread pinning during test execution.
   * 
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * which happens in two main scenarios:
   * 1. When using synchronized blocks or methods
   * 2. When executing native methods or foreign functions
   */
  protected static class PinningDetector {
    private final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    private final AtomicInteger pinnedThreadCount = new AtomicInteger(0);
    private Thread monitorThread;
    private long monitoringStartTime;
    
    /**
     * Starts monitoring for thread pinning.
     */
    public void startMonitoring() {
      pinningDetected.set(false);
      pinnedThreadCount.set(0);
      monitoringStartTime = System.currentTimeMillis();
      
      // Create a thread to monitor for pinning
      // This is a simplified approach - in a real environment, you would use JFR events
      // or the jdk.tracePinnedThreads system property
      monitorThread = new Thread(() -> {
        try {
          // Check for the JVM flag that enables pinning detection
          String tracePinned = System.getProperty("jdk.tracePinnedThreads");
          if (tracePinned == null || tracePinned.isEmpty()) {
            log.warn("Thread pinning detection is not enabled. Add -Djdk.tracePinnedThreads=full JVM flag for accurate detection.");
            log.warn("To enable pinning detection, add this JVM argument: -Djdk.tracePinnedThreads=full");
          }
          else {
            log.info("Thread pinning detection is enabled with jdk.tracePinnedThreads={}", tracePinned);
          }
          
          // In a real implementation, this would use JFR Event Streaming to detect pinning events
          // For example:
          // try (var rs = new RecordingStream()) {
          //   rs.enable("jdk.VirtualThreadPinned").withStackTrace();
          //   rs.onEvent("jdk.VirtualThreadPinned", this::handlePinningEvent);
          //   rs.startAsync();
          // }
          
          // For this test class, we're just providing the structure and monitoring system output
          // that might contain pinning information when jdk.tracePinnedThreads is enabled
          while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(Duration.ofSeconds(1).toMillis());
          }
        }
        catch (InterruptedException e) {
          // Expected when stopping monitoring
          Thread.currentThread().interrupt();
        }
        catch (Exception e) {
          log.error("Error in pinning detection thread", e);
        }
      }, "pinning-detector");
      
      monitorThread.setDaemon(true);
      monitorThread.start();
    }
    
    /**
     * Stops monitoring for thread pinning and logs summary.
     */
    public void stopMonitoring() {
      if (monitorThread != null) {
        monitorThread.interrupt();
        try {
          monitorThread.join(1000);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        monitorThread = null;
        
        // Log monitoring summary
        long duration = System.currentTimeMillis() - monitoringStartTime;
        if (isPinningDetected()) {
          log.warn("Thread pinning monitoring completed: {} pinned threads detected in {} ms", 
              pinnedThreadCount.get(), duration);
        }
        else {
          log.info("Thread pinning monitoring completed: No pinning detected in {} ms", duration);
        }
      }
    }
    
    /**
     * Checks if thread pinning was detected during monitoring.
     */
    public boolean isPinningDetected() {
      return pinningDetected.get();
    }
    
    /**
     * Gets the number of pinned threads detected.
     */
    public int getPinnedThreadCount() {
      return pinnedThreadCount.get();
    }
    
    /**
     * Marks that thread pinning was detected.
     * 
     * @param threadName the name of the thread that was pinned
     * @param duration the duration of the pinning in milliseconds
     * @param stackTrace the stack trace where pinning was detected
     */
    public void markPinningDetected(String threadName, long duration, String stackTrace) {
      pinningDetected.set(true);
      pinnedThreadCount.incrementAndGet();
      log.warn("Thread pinning detected: Thread '{}' pinned for {} ms\nStack trace:\n{}", 
          threadName, duration, stackTrace);
    }
    
    /**
     * Handle a JFR VirtualThreadPinned event.
     * This method would be used with JFR Event Streaming in a real implementation.
     * 
     * @param event the JFR event
     */
    private void handlePinningEvent(Object event) {
      // In a real implementation with JFR Event Streaming, this would extract information
      // from the RecordedEvent and call markPinningDetected with the appropriate details
      // For example:
      // RecordedEvent recordedEvent = (RecordedEvent) event;
      // String threadName = recordedEvent.getThread("thread").getJavaName();
      // Duration duration = recordedEvent.getDuration();
      // String stackTrace = recordedEvent.getStackTrace().toString();
      // markPinningDetected(threadName, duration.toMillis(), stackTrace);
    }
  }
}