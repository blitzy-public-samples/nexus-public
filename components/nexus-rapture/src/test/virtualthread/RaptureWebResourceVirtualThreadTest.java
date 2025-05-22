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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.rapture.UiPluginDescriptorSupport;
import org.sonatype.nexus.rapture.internal.RaptureWebResourceBundle;
import org.sonatype.nexus.rapture.internal.state.StateComponent;
import org.sonatype.nexus.ui.UiPluginDescriptor;

import com.google.inject.util.Providers;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link RaptureWebResourceBundle} component with Java 21 Virtual Threads to validate
 * its performance and concurrency characteristics.
 * 
 * This test class measures the throughput and resource utilization of web resource generation
 * under high concurrency, comparing platform threads versus virtual threads.
 */
@ExtendWith(MockitoExtension.class)
public class RaptureWebResourceVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int WARMUP_ITERATIONS = 10;
  private static final int TEST_ITERATIONS = 3;
  private static final double VIRTUAL_THREAD_THROUGHPUT_FACTOR = 1.5; // Virtual threads should be at least 1.5x faster
  private static final double VIRTUAL_THREAD_MEMORY_FACTOR = 0.5; // Virtual threads should use at most 50% of the memory
  
  @Mock
  private ApplicationVersion applicationVersion;

  @Mock
  private HttpServletRequest httpServletRequest;

  @Mock
  private StateComponent stateComponent;

  @Mock
  private TemplateHelper templateHelper;

  private RaptureWebResourceBundle underTest;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setup() {
    BaseUrlHolder.set("http://baseurl/", ".");

    when(httpServletRequest.getParameter("debug")).thenReturn("false");

    underTest = new RaptureWebResourceBundle(
        applicationVersion, 
        Providers.of(httpServletRequest), 
        Providers.of(stateComponent),
        templateHelper, 
        asList(new UiPluginDescriptorImpl()),
        asList(new ExtJsUiPluginDescriptorImpl("test-1"), new ExtJsUiPluginDescriptorImpl("test-2")), 
        null, 
        false);
    
    // Create executors for platform threads and virtual threads
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests the performance of generating web resources using platform threads vs virtual threads.
   * This test validates that virtual threads provide better throughput and lower memory usage
   * when handling a large number of concurrent requests.
   */
  @Test
  public void testConcurrentResourceGenerationPerformance() throws Exception {
    // Warm up to avoid JIT compilation affecting results
    log.info("Warming up with {} iterations", WARMUP_ITERATIONS);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentTest(10, platformThreadExecutor);
      runConcurrentTest(10, virtualThreadExecutor);
    }
    
    // Run the actual performance test
    log.info("Running performance test with {} concurrent requests, {} iterations", 
        CONCURRENT_REQUESTS, TEST_ITERATIONS);
    
    PerformanceResult platformResult = new PerformanceResult();
    PerformanceResult virtualResult = new PerformanceResult();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      // Measure platform threads performance
      log.info("Iteration {}: Testing with platform threads", i + 1);
      platformResult.addSample(runConcurrentTest(CONCURRENT_REQUESTS, platformThreadExecutor));
      
      // Measure virtual threads performance
      log.info("Iteration {}: Testing with virtual threads", i + 1);
      virtualResult.addSample(runConcurrentTest(CONCURRENT_REQUESTS, virtualThreadExecutor));
      
      // Force GC to get more accurate memory measurements
      System.gc();
      Thread.sleep(1000);
    }
    
    // Log and assert results
    log.info("Platform threads - Avg throughput: {} ops/sec, Avg memory: {} MB", 
        platformResult.getAvgThroughput(), platformResult.getAvgMemoryUsageMB());
    log.info("Virtual threads - Avg throughput: {} ops/sec, Avg memory: {} MB", 
        virtualResult.getAvgThroughput(), virtualResult.getAvgMemoryUsageMB());
    
    // Virtual threads should have higher throughput
    assertThat("Virtual threads should have higher throughput than platform threads",
        virtualResult.getAvgThroughput(), 
        greaterThan(platformResult.getAvgThroughput() * VIRTUAL_THREAD_THROUGHPUT_FACTOR));
    
    // Virtual threads should use less memory
    assertThat("Virtual threads should use less memory than platform threads",
        virtualResult.getAvgMemoryUsageMB(), 
        lessThan(platformResult.getAvgMemoryUsageMB() * VIRTUAL_THREAD_MEMORY_FACTOR));
  }
  
  /**
   * Tests that the RaptureWebResourceBundle remains thread-safe when accessed concurrently
   * by thousands of virtual threads. This validates that resource generation doesn't produce
   * inconsistent results or exceptions under extreme concurrency.
   */
  @Test
  public void testThreadSafetyWithVirtualThreads() throws Exception {
    int concurrentThreads = 5000; // Test with 5000 concurrent virtual threads
    CountDownLatch latch = new CountDownLatch(concurrentThreads);
    AtomicReference<Throwable> firstException = new AtomicReference<>();
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start 5000 virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrentThreads; i++) {
        executor.submit(() -> {
          try {
            // Access various methods of RaptureWebResourceBundle concurrently
            List<String> configs = underTest.getExtJsPluginConfigs();
            List<String> namespaces = underTest.getExtJsNamespaces();
            List<URI> styles = underTest.getStyles();
            List<URI> scripts = underTest.getScripts();
            
            // Verify results are valid
            assertThat(configs, notNullValue());
            assertThat(namespaces, notNullValue());
            assertThat(styles, notNullValue());
            assertThat(scripts, notNullValue());
            
            successCount.incrementAndGet();
          } 
          catch (Throwable t) {
            // Record the first exception that occurs
            firstException.compareAndSet(null, t);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout after 30 seconds
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Check results
      assertThat("All virtual threads should complete within the timeout", completed, is(true));
      assertThat("No exceptions should occur during concurrent access", firstException.get(), is(null));
      assertThat("All operations should succeed", successCount.get(), is(concurrentThreads));
    }
  }
  
  /**
   * Runs a concurrent test with the specified number of requests using the given executor.
   * 
   * @param concurrentRequests the number of concurrent requests to simulate
   * @param executor the executor service to use (platform or virtual thread executor)
   * @return a TestResult containing throughput and memory usage metrics
   */
  private TestResult runConcurrentTest(int concurrentRequests, ExecutorService executor) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentRequests);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Record memory before test
    long memoryBefore = getUsedMemory();
    
    // Submit tasks to executor
    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Generate resources
          underTest.getExtJsPluginConfigs();
          underTest.getExtJsNamespaces();
          underTest.getStyles();
          underTest.getScripts();
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during resource generation", e);
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start the test and measure time
    long startTime = System.nanoTime();
    startLatch.countDown(); // Release all threads to start simultaneously
    
    // Wait for all threads to complete
    completionLatch.await();
    long endTime = System.nanoTime();
    
    // Record memory after test
    long memoryAfter = getUsedMemory();
    
    // Calculate metrics
    double durationSeconds = Duration.ofNanos(endTime - startTime).toMillis() / 1000.0;
    double throughput = concurrentRequests / durationSeconds;
    double memoryUsageMB = (memoryAfter - memoryBefore) / (1024.0 * 1024.0);
    
    // Ensure no errors occurred
    assertThat("No errors should occur during resource generation", errorCount.get(), is(0));
    
    return new TestResult(throughput, memoryUsageMB);
  }
  
  /**
   * Gets the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Represents the result of a single performance test run.
   */
  private static class TestResult {
    private final double throughput;
    private final double memoryUsageMB;
    
    public TestResult(double throughput, double memoryUsageMB) {
      this.throughput = throughput;
      this.memoryUsageMB = memoryUsageMB;
    }
    
    public double getThroughput() {
      return throughput;
    }
    
    public double getMemoryUsageMB() {
      return memoryUsageMB;
    }
  }
  
  /**
   * Aggregates multiple test results to calculate averages.
   */
  private static class PerformanceResult {
    private final AtomicLong throughputSum = new AtomicLong(0);
    private final AtomicLong memorySum = new AtomicLong(0);
    private final AtomicInteger sampleCount = new AtomicInteger(0);
    
    public void addSample(TestResult result) {
      throughputSum.addAndGet(Math.round(result.getThroughput() * 1000));
      memorySum.addAndGet(Math.round(result.getMemoryUsageMB() * 1000));
      sampleCount.incrementAndGet();
    }
    
    public double getAvgThroughput() {
      return sampleCount.get() > 0 ? 
          throughputSum.get() / (sampleCount.get() * 1000.0) : 0;
    }
    
    public double getAvgMemoryUsageMB() {
      return sampleCount.get() > 0 ? 
          memorySum.get() / (sampleCount.get() * 1000.0) : 0;
    }
  }
  
  /**
   * Implementation of UiPluginDescriptor for testing.
   */
  private final class UiPluginDescriptorImpl
      extends org.sonatype.nexus.ui.UiPluginDescriptorSupport
  {
    public UiPluginDescriptorImpl() {
      super("test");
    }

    @Override
    public List<String> getStyles() {
      return asList("/react-style-1-" + getName() + ".css", "/react-style-2-" + getName() + ".css");
    }

    @Override
    public List<String> getScripts(final boolean isDebug) {
      String suffix = getName() + (isDebug ? "-debug" : "-prod") + ".js";
      return asList("/react-script-1-" + suffix, "/react-script-2-" + suffix);
    }
  }

  /**
   * Implementation of ExtJs UiPluginDescriptor for testing.
   */
  private final class ExtJsUiPluginDescriptorImpl
      extends org.sonatype.nexus.rapture.UiPluginDescriptorSupport
  {
    public ExtJsUiPluginDescriptorImpl(final String artifactId) {
      super(artifactId);
    }

    @Override
    public String getConfigClassName() {
      return "id-" + getPluginId();
    }

    @Override
    public String getNamespace() {
      return "namespace-" + getPluginId();
    }

    @Override
    public boolean hasStyle() {
      return true;
    }

    @Override
    public boolean hasScript() {
      return true;
    }

    @Override
    public List<String> getScripts(final boolean isDebug) {
      return asList("/extjs-script-1-" + getPluginId() + ".js", "/extjs-script-2-" + getPluginId() + ".js");
    }
  }
}