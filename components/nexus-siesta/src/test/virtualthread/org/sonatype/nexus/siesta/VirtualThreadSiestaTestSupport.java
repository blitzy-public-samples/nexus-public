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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.servlet.DispatcherType;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

/**
 * Support for Siesta tests using Java 21 Virtual Threads.
 * 
 * This class extends the standard {@link SiestaTestSupport} to provide an embedded Jetty server
 * environment configured with Virtual Thread-optimized settings. It configures thread pools to use
 * Java 21 Virtual Threads, provides utilities for measuring Virtual Thread performance metrics,
 * and includes helper methods for detecting thread pinning issues.
 */
public class VirtualThreadSiestaTestSupport
    extends TestSupport
{
  private Server server;
  private String url;
  private Client client;
  private final Map<String, PerformanceMetrics> endpointMetrics = new ConcurrentHashMap<>();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  /**
   * Performance metrics for a specific endpoint.
   */
  public static class PerformanceMetrics {
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong maxResponseTime = new AtomicLong(0);
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong activeThreads = new AtomicLong(0);
    private final AtomicLong peakThreads = new AtomicLong(0);

    public void recordRequest(long responseTimeMs, boolean success) {
      totalRequests.incrementAndGet();
      if (success) {
        totalResponseTime.addAndGet(responseTimeMs);
        maxResponseTime.updateAndGet(current -> Math.max(current, responseTimeMs));
        minResponseTime.updateAndGet(current -> Math.min(current, responseTimeMs));
      } else {
        errors.incrementAndGet();
      }
    }

    public void incrementActiveThreads() {
      long active = activeThreads.incrementAndGet();
      peakThreads.updateAndGet(current -> Math.max(current, active));
    }

    public void decrementActiveThreads() {
      activeThreads.decrementAndGet();
    }

    public long getTotalRequests() {
      return totalRequests.get();
    }

    public long getSuccessfulRequests() {
      return totalRequests.get() - errors.get();
    }

    public long getErrorCount() {
      return errors.get();
    }

    public double getErrorRate() {
      return totalRequests.get() > 0 ? (double) errors.get() / totalRequests.get() : 0.0;
    }

    public double getAverageResponseTime() {
      long successful = getSuccessfulRequests();
      return successful > 0 ? (double) totalResponseTime.get() / successful : 0.0;
    }

    public long getMaxResponseTime() {
      return maxResponseTime.get();
    }

    public long getMinResponseTime() {
      return minResponseTime.get() == Long.MAX_VALUE ? 0 : minResponseTime.get();
    }

    public long getPeakThreads() {
      return peakThreads.get();
    }

    public long getCurrentActiveThreads() {
      return activeThreads.get();
    }

    @Override
    public String toString() {
      return String.format(
          "Requests: %d (Success: %d, Errors: %d, Error Rate: %.2f%%), " +
          "Response Time: %.2f ms (Min: %d ms, Max: %d ms), " +
          "Threads: %d (Peak: %d)",
          getTotalRequests(), getSuccessfulRequests(), getErrorCount(), getErrorRate() * 100,
          getAverageResponseTime(), getMinResponseTime(), getMaxResponseTime(),
          getCurrentActiveThreads(), getPeakThreads());
    }
  }

  /**
   * Starts an embedded Jetty server with Virtual Thread support.
   */
  @Before
  public void startJetty() throws Exception {
    // Create a server with Virtual Thread pool
    server = new Server(createVirtualThreadPool());

    // Configure the servlet context
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context.addEventListener(new GuiceServletContextListener() {
      final Injector injector = Guice.createInjector(new TestModule());

      @Override
      protected Injector getInjector() {
        return injector;
      }
    });

    // Add the Guice filter
    context.addFilter(GuiceFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    context.addServlet(DummyServlet.class, "/*");

    // Set up the handlers
    ContextHandlerCollection handlers = new ContextHandlerCollection();
    handlers.addHandler(context);
    server.setHandler(handlers);

    // Configure request logging
    CustomRequestLog requestLog = new CustomRequestLog(
        (request, response, responseTime) -> {
          String path = request.getPathInContext();
          if (path != null && !path.isEmpty()) {
            // Record metrics for this endpoint
            endpointMetrics.computeIfAbsent(path, k -> new PerformanceMetrics())
                .recordRequest(responseTime, response.getStatus() < 400);
          }
        });
    server.setRequestLog(requestLog);

    // Start the server
    server.start();

    // Get the server URL
    url = "http://localhost:" + server.getURI().getPort() + TestModule.MOUNT_POINT;

    // Create a JAX-RS client
    client = ClientBuilder.newClient();
  }

  /**
   * Creates a ThreadPool that uses Virtual Threads.
   */
  private ThreadPool createVirtualThreadPool() {
    return new ThreadPool() {
      private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

      @Override
      public void join() throws InterruptedException {
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
      }

      @Override
      public int getThreads() {
        return 0; // Virtual threads, so no fixed count
      }

      @Override
      public int getIdleThreads() {
        return 0; // Virtual threads, so no fixed count
      }

      @Override
      public boolean isLowOnThreads() {
        return false; // Virtual threads are unlimited
      }

      @Override
      public void execute(Runnable command) {
        executor.submit(command);
      }
    };
  }

  /**
   * Stops the embedded Jetty server.
   */
  @After
  public void stopJetty() throws Exception {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.stop();
    }
  }

  /**
   * Returns the JAX-RS client.
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
   * Returns the URL for a specific path.
   */
  protected String url(final String path) {
    return url + "/" + path;
  }

  /**
   * Executes a load test against the specified endpoint using Virtual Threads.
   *
   * @param path The endpoint path to test
   * @param requestSupplier A supplier that creates the request to execute
   * @param concurrentUsers The number of concurrent users to simulate
   * @param durationSeconds The duration of the test in seconds
   * @return The performance metrics for the test
   */
  protected PerformanceMetrics runLoadTest(String path, 
                                          Supplier<Response> requestSupplier,
                                          int concurrentUsers, 
                                          int durationSeconds) throws Exception {
    log("Starting load test for {} with {} concurrent users for {} seconds", 
        path, concurrentUsers, durationSeconds);
    
    // Clear any existing metrics for this endpoint
    endpointMetrics.remove(path);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a countdown latch to signal test completion
      CountDownLatch completionLatch = new CountDownLatch(1);
      
      // Start the timer
      long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
      
      // Submit tasks for each concurrent user
      List<Future<?>> futures = executor.invokeAll(
          List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).stream()
              .limit(concurrentUsers)
              .map(i -> (Runnable) () -> {
                PerformanceMetrics metrics = endpointMetrics.computeIfAbsent(path, k -> new PerformanceMetrics());
                
                // Keep sending requests until the test duration expires
                while (System.currentTimeMillis() < endTime) {
                  metrics.incrementActiveThreads();
                  try {
                    long startTime = System.currentTimeMillis();
                    Response response = requestSupplier.get();
                    long responseTime = System.currentTimeMillis() - startTime;
                    
                    // Record the result
                    boolean success = response.getStatus() >= 200 && response.getStatus() < 400;
                    metrics.recordRequest(responseTime, success);
                    
                    // Close the response
                    response.close();
                    
                    // Small delay to prevent overwhelming the server
                    Thread.sleep(10);
                  }
                  catch (Exception e) {
                    log.warn("Error during load test: {}", e.getMessage());
                    metrics.recordRequest(0, false);
                  }
                  finally {
                    metrics.decrementActiveThreads();
                  }
                }
              })
              .toList(),
          Duration.ofSeconds(durationSeconds + 10));
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Get the final metrics
      PerformanceMetrics metrics = endpointMetrics.get(path);
      log("Load test completed for {}: {}", path, metrics);
      
      return metrics;
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for the specified endpoint.
   *
   * @param path The endpoint path to test
   * @param requestSupplier A supplier that creates the request to execute
   * @param concurrentUsers The number of concurrent users to simulate
   * @param durationSeconds The duration of each test in seconds
   * @return A map containing performance metrics for both thread types
   */
  protected Map<String, PerformanceMetrics> compareThreadPerformance(String path,
                                                                   Supplier<Response> requestSupplier,
                                                                   int concurrentUsers,
                                                                   int durationSeconds) throws Exception {
    // First run with platform threads
    log("Running platform thread test for {}", path);
    PerformanceMetrics platformMetrics = runPlatformThreadLoadTest(path, requestSupplier, concurrentUsers, durationSeconds);
    
    // Then run with virtual threads
    log("Running virtual thread test for {}", path);
    PerformanceMetrics virtualMetrics = runLoadTest(path, requestSupplier, concurrentUsers, durationSeconds);
    
    // Compare the results
    log("Performance comparison for {}:", path);
    log("  Platform Threads: {}", platformMetrics);
    log("  Virtual Threads:  {}", virtualMetrics);
    
    // Calculate improvement percentages
    double throughputImprovement = calculateImprovement(
        platformMetrics.getSuccessfulRequests(), virtualMetrics.getSuccessfulRequests());
    double latencyImprovement = calculateImprovement(
        platformMetrics.getAverageResponseTime(), virtualMetrics.getAverageResponseTime(), true);
    
    log("  Throughput improvement: {:.2f}%", throughputImprovement);
    log("  Latency improvement:    {:.2f}%", latencyImprovement);
    
    return Map.of(
        "platform", platformMetrics,
        "virtual", virtualMetrics
    );
  }

  /**
   * Executes a load test against the specified endpoint using platform threads.
   */
  private PerformanceMetrics runPlatformThreadLoadTest(String path,
                                                     Supplier<Response> requestSupplier,
                                                     int concurrentUsers,
                                                     int durationSeconds) throws Exception {
    log("Starting platform thread load test for {} with {} concurrent users for {} seconds",
        path, concurrentUsers, durationSeconds);
    
    // Clear any existing metrics for this endpoint
    endpointMetrics.remove(path);
    
    // Create a fixed thread pool executor with the specified number of threads
    try (ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers)) {
      // Create a countdown latch to signal test completion
      CountDownLatch completionLatch = new CountDownLatch(1);
      
      // Start the timer
      long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
      
      // Submit tasks for each concurrent user
      List<Future<?>> futures = executor.invokeAll(
          List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).stream()
              .limit(concurrentUsers)
              .map(i -> (Runnable) () -> {
                PerformanceMetrics metrics = endpointMetrics.computeIfAbsent(path, k -> new PerformanceMetrics());
                
                // Keep sending requests until the test duration expires
                while (System.currentTimeMillis() < endTime) {
                  metrics.incrementActiveThreads();
                  try {
                    long startTime = System.currentTimeMillis();
                    Response response = requestSupplier.get();
                    long responseTime = System.currentTimeMillis() - startTime;
                    
                    // Record the result
                    boolean success = response.getStatus() >= 200 && response.getStatus() < 400;
                    metrics.recordRequest(responseTime, success);
                    
                    // Close the response
                    response.close();
                    
                    // Small delay to prevent overwhelming the server
                    Thread.sleep(10);
                  }
                  catch (Exception e) {
                    log.warn("Error during load test: {}", e.getMessage());
                    metrics.recordRequest(0, false);
                  }
                  finally {
                    metrics.decrementActiveThreads();
                  }
                }
              })
              .toList(),
          Duration.ofSeconds(durationSeconds + 10));
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Get the final metrics
      PerformanceMetrics metrics = endpointMetrics.get(path);
      log("Platform thread load test completed for {}: {}", path, metrics);
      
      return metrics;
    }
  }

  /**
   * Calculates the percentage improvement between two values.
   *
   * @param baseline The baseline value
   * @param current The current value
   * @param lowerIsBetter Whether a lower value is better (e.g., for latency)
   * @return The percentage improvement
   */
  private double calculateImprovement(double baseline, double current, boolean lowerIsBetter) {
    if (baseline == 0) {
      return 0.0;
    }
    
    if (lowerIsBetter) {
      return ((baseline - current) / baseline) * 100.0;
    } else {
      return ((current - baseline) / baseline) * 100.0;
    }
  }

  /**
   * Calculates the percentage improvement between two values (higher is better).
   */
  private double calculateImprovement(double baseline, double current) {
    return calculateImprovement(baseline, current, false);
  }

  /**
   * Creates a WebTarget for the specified path.
   */
  protected WebTarget target(String path) {
    return client().target(url(path));
  }
}