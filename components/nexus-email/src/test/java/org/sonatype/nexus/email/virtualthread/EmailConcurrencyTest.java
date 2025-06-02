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
package org.sonatype.nexus.email.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.internal.email.EmailConfigurationStore;
import org.sonatype.nexus.internal.email.EmailManagerImpl;
import org.sonatype.nexus.security.UserIdHelper;
import org.sonatype.nexus.ssl.TrustStore;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * High-concurrency stress testing for the email subsystem using Java 21 Virtual Threads
 * versus traditional platform threads. This test class measures and compares performance metrics,
 * validates correctness under load, and verifies that Virtual Threads significantly
 * improve throughput and resource utilization in email sending operations.
 * 
 * <p>The test methodology:</p>
 * <ul>
 *   <li>Compares Virtual Threads (using ThreadFactory.ofVirtual().factory()) with Platform Threads 
 *       (using ThreadFactory.ofPlatform().factory())</li>
 *   <li>Tests with different concurrency levels (100, 1000, 10000 concurrent clients)</li>
 *   <li>Measures key performance metrics: throughput, response time, memory usage</li>
 *   <li>Runs multiple iterations with warm-up cycles to ensure reliable results</li>
 *   <li>Simulates I/O-bound operations typical in email sending scenarios</li>
 * </ul>
 * 
 * <p>This test demonstrates the benefits of Java 21's Virtual Threads for I/O-bound operations,
 * particularly in scenarios with high concurrency where traditional thread pools would be limited
 * by the number of available platform threads.</p>
 */
@Category(VirtualThreadTestGroup.class)
public class EmailConcurrencyTest
    extends TestSupport
{
  private static final int SMALL_CONCURRENCY = 100;
  private static final int MEDIUM_CONCURRENCY = 1000;
  private static final int LARGE_CONCURRENCY = 10000;
  
  private static final int WARMUP_ITERATIONS = 3;
  private static final int TEST_ITERATIONS = 5;
  private static final long OPERATION_DELAY_MS = 50; // Simulated email sending delay

  @Mock
  private EventManager eventManager;

  @Mock
  private EmailConfigurationStore emailConfigurationStore;

  @Mock
  private TrustStore trustStore;

  @Mock
  private SecretsService secretsService;

  private EmailManagerImpl emailManager;
  private EmailConfiguration emailConfig;
  private AutoCloseable mocks;

  @Before
  public void setup() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    
    // Mock UserIdHelper.get() to return "userId"
    try (var userIdHelperMock = mockStatic(UserIdHelper.class)) {
      userIdHelperMock.when(UserIdHelper::get).thenReturn("userId");
    }

    // Setup email configuration
    emailConfig = mock(EmailConfiguration.class);
    when(emailConfig.isEnabled()).thenReturn(true);
    when(emailConfig.getHost()).thenReturn("example.com");
    when(emailConfig.getPort()).thenReturn(25);
    when(emailConfig.getFromAddress()).thenReturn("sender@example.com");
    when(emailConfig.getUsername()).thenReturn("user");
    when(emailConfig.isStartTlsEnabled()).thenReturn(true);
    when(emailConfig.isStartTlsRequired()).thenReturn(false);
    when(emailConfig.isSslOnConnectEnabled()).thenReturn(false);
    when(emailConfig.isSslCheckServerIdentityEnabled()).thenReturn(false);
    when(emailConfig.isNexusTrustStoreEnabled()).thenReturn(true);
    
    when(emailConfigurationStore.load()).thenReturn(emailConfig);
    
    // Create email manager with partial mocking to simulate network delay without actually sending emails
    emailManager = spy(new EmailManagerImpl(eventManager, emailConfigurationStore, trustStore, null, null, secretsService));
    
    // Mock the send method to simulate network delay
    doAnswer(invocation -> {
      // Simulate email sending delay
      Thread.sleep(OPERATION_DELAY_MS);
      return null;
    }).when(emailManager).send(any(Email.class));
  }

  @After
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  /**
   * Tests email sending performance with a small number of concurrent clients (100) using both
   * platform threads and virtual threads.
   * 
   * <p>This test case represents a moderate load scenario that traditional thread pools
   * can typically handle, but where Virtual Threads may still show some performance advantages
   * due to their lightweight nature and more efficient resource utilization.</p>
   */
  @Test
  public void testSmallConcurrency() throws Exception {
    logger.info("Testing with {} concurrent clients", SMALL_CONCURRENCY);
    
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrencyTest(SMALL_CONCURRENCY, true);
      runConcurrencyTest(SMALL_CONCURRENCY, false);
    }
    
    // Actual test
    PerformanceResult platformResult = new PerformanceResult();
    PerformanceResult virtualResult = new PerformanceResult();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      platformResult.addResult(runConcurrencyTest(SMALL_CONCURRENCY, false));
      virtualResult.addResult(runConcurrencyTest(SMALL_CONCURRENCY, true));
    }
    
    logResults("Small Concurrency", platformResult, virtualResult);
    assertPerformanceImprovement(platformResult, virtualResult);
  }

  /**
   * Tests email sending performance with a medium number of concurrent clients (1000) using both
   * platform threads and virtual threads.
   * 
   * <p>This test case represents a high load scenario where traditional thread pools
   * start to show limitations due to the overhead of maintaining many platform threads.
   * Virtual Threads are expected to demonstrate significant advantages in throughput
   * and resource efficiency at this concurrency level.</p>
   */
  @Test
  public void testMediumConcurrency() throws Exception {
    logger.info("Testing with {} concurrent clients", MEDIUM_CONCURRENCY);
    
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrencyTest(MEDIUM_CONCURRENCY, true);
      runConcurrencyTest(MEDIUM_CONCURRENCY, false);
    }
    
    // Actual test
    PerformanceResult platformResult = new PerformanceResult();
    PerformanceResult virtualResult = new PerformanceResult();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      platformResult.addResult(runConcurrencyTest(MEDIUM_CONCURRENCY, false));
      virtualResult.addResult(runConcurrencyTest(MEDIUM_CONCURRENCY, true));
    }
    
    logResults("Medium Concurrency", platformResult, virtualResult);
    assertPerformanceImprovement(platformResult, virtualResult);
  }

  /**
   * Tests email sending performance with a large number of concurrent clients (10000) using both
   * platform threads and virtual threads.
   * 
   * <p>This test case represents an extreme load scenario that would typically be impossible
   * for traditional thread pools to handle efficiently due to the high memory overhead
   * of platform threads. Virtual Threads are expected to excel in this scenario,
   * demonstrating their ability to handle massive concurrency with minimal resource usage.</p>
   * 
   * <p>This test validates one of the key benefits of Java 21's Virtual Threads: the ability
   * to efficiently handle thousands of concurrent operations that would be impractical
   * with traditional threading models.</p>
   */
  @Test
  public void testLargeConcurrency() throws Exception {
    logger.info("Testing with {} concurrent clients", LARGE_CONCURRENCY);
    
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrencyTest(LARGE_CONCURRENCY, true);
      runConcurrencyTest(LARGE_CONCURRENCY, false);
    }
    
    // Actual test
    PerformanceResult platformResult = new PerformanceResult();
    PerformanceResult virtualResult = new PerformanceResult();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      platformResult.addResult(runConcurrencyTest(LARGE_CONCURRENCY, false));
      virtualResult.addResult(runConcurrencyTest(LARGE_CONCURRENCY, true));
    }
    
    logResults("Large Concurrency", platformResult, virtualResult);
    assertPerformanceImprovement(platformResult, virtualResult);
  }

  /**
   * Runs a concurrency test with the specified number of concurrent clients and thread type.
   * This method is the core of the performance testing methodology, creating either virtual
   * or platform threads based on the parameters, and measuring various performance metrics.
   *
   * <p>The method performs the following steps:</p>
   * <ol>
   *   <li>Creates an appropriate thread factory and executor service based on the thread type</li>
   *   <li>Sets up synchronization mechanisms (CountDownLatch) to ensure accurate timing</li>
   *   <li>Measures memory usage before the test</li>
   *   <li>Submits the specified number of concurrent email sending tasks</li>
   *   <li>Measures total execution time, individual response times, and success/error counts</li>
   *   <li>Measures memory usage after the test</li>
   *   <li>Calculates performance metrics (throughput, average response time, etc.)</li>
   * </ol>
   *
   * @param concurrency the number of concurrent clients
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return the test result containing performance metrics
   */
  private TestResult runConcurrencyTest(int concurrency, boolean useVirtualThreads) throws Exception {
    // Create thread factory based on the specified thread type
    ThreadFactory threadFactory = useVirtualThreads ? 
        ThreadFactory.ofVirtual().factory() : 
        ThreadFactory.ofPlatform().factory();
    
    // Create executor service with the thread factory
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(Math.min(concurrency, Runtime.getRuntime().availableProcessors() * 2), threadFactory);
    
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(concurrency);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      List<Long> responseTimes = new ArrayList<>(concurrency);
      
      // Record memory usage before test
      System.gc(); // Request garbage collection to get more accurate memory readings
      long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      
      // Submit tasks to the executor
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Record start time
            long startTime = System.nanoTime();
            
            // Send email
            sendTestEmail();
            
            // Record response time
            long responseTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            synchronized (responseTimes) {
              responseTimes.add(responseTime);
            }
            
            // Increment success count
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            // Increment error count
            errorCount.incrementAndGet();
            logger.error("Error sending email", e);
          } 
          finally {
            // Signal completion
            completionLatch.countDown();
          }
        });
      }
      
      // Start the test
      long startTime = System.currentTimeMillis();
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await();
      long endTime = System.currentTimeMillis();
      
      // Record memory usage after test
      System.gc(); // Request garbage collection to get more accurate memory readings
      long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      
      // Calculate metrics
      long totalTime = endTime - startTime;
      double throughput = (double) successCount.get() / (totalTime / 1000.0);
      double avgResponseTime = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
      long memoryUsed = memoryAfter - memoryBefore;
      
      // Create and return test result
      return new TestResult(concurrency, useVirtualThreads, totalTime, throughput, avgResponseTime, 
          successCount.get(), errorCount.get(), memoryUsed);
    } 
    finally {
      // Shutdown executor
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Sends a test email using the email manager.
   */
  private void sendTestEmail() throws Exception {
    SimpleEmail email = new SimpleEmail();
    email.setSubject("Test Email");
    email.setMsg("This is a test email.");
    email.addTo("recipient@example.com");
    
    emailManager.send(email);
  }

  /**
   * Logs the results of the performance tests.
   *
   * @param testName the name of the test
   * @param platformResult the platform thread test results
   * @param virtualResult the virtual thread test results
   */
  private void logResults(String testName, PerformanceResult platformResult, PerformanceResult virtualResult) {
    logger.info("----- {} Test Results -----", testName);
    logger.info("Platform Threads:");
    logger.info("  Avg Total Time: {} ms", platformResult.getAvgTotalTime());
    logger.info("  Avg Throughput: {} emails/sec", platformResult.getAvgThroughput());
    logger.info("  Avg Response Time: {} ms", platformResult.getAvgResponseTime());
    logger.info("  Avg Success Rate: {}%", platformResult.getAvgSuccessRate());
    logger.info("  Avg Memory Used: {} bytes", platformResult.getAvgMemoryUsed());
    
    logger.info("Virtual Threads:");
    logger.info("  Avg Total Time: {} ms", virtualResult.getAvgTotalTime());
    logger.info("  Avg Throughput: {} emails/sec", virtualResult.getAvgThroughput());
    logger.info("  Avg Response Time: {} ms", virtualResult.getAvgResponseTime());
    logger.info("  Avg Success Rate: {}%", virtualResult.getAvgSuccessRate());
    logger.info("  Avg Memory Used: {} bytes", virtualResult.getAvgMemoryUsed());
    
    // Calculate improvement percentages
    double throughputImprovement = ((virtualResult.getAvgThroughput() / platformResult.getAvgThroughput()) - 1) * 100;
    double responseTimeImprovement = ((platformResult.getAvgResponseTime() / virtualResult.getAvgResponseTime()) - 1) * 100;
    double memoryImprovement = ((platformResult.getAvgMemoryUsed() / virtualResult.getAvgMemoryUsed()) - 1) * 100;
    
    logger.info("Improvements with Virtual Threads:");
    logger.info("  Throughput: {}%", String.format("%.2f", throughputImprovement));
    logger.info("  Response Time: {}%", String.format("%.2f", responseTimeImprovement));
    logger.info("  Memory Usage: {}%", String.format("%.2f", memoryImprovement));
  }

  /**
   * Asserts that virtual threads provide a performance improvement over platform threads.
   * This method validates the core hypothesis of the test: that Java 21's Virtual Threads
   * offer significant performance advantages for I/O-bound operations under high concurrency.
   *
   * <p>The method checks three key performance metrics:</p>
   * <ol>
   *   <li>Throughput: Virtual threads should process more emails per second</li>
   *   <li>Response time: Virtual threads should have lower average response times</li>
   *   <li>Memory usage: Virtual threads should use less memory despite higher concurrency</li>
   * </ol>
   *
   * <p>Additionally, it verifies that both thread types maintain 100% success rate,
   * ensuring that the performance improvements don't come at the cost of reliability.</p>
   *
   * @param platformResult the platform thread test results
   * @param virtualResult the virtual thread test results
   */
  private void assertPerformanceImprovement(PerformanceResult platformResult, PerformanceResult virtualResult) {
    // Assert that virtual threads have higher throughput
    assertThat("Virtual threads should have higher throughput", 
        virtualResult.getAvgThroughput(), greaterThan(platformResult.getAvgThroughput()));
    
    // Assert that virtual threads have lower response time
    assertThat("Virtual threads should have lower response time", 
        virtualResult.getAvgResponseTime(), lessThan(platformResult.getAvgResponseTime()));
    
    // Assert that virtual threads have lower memory usage
    assertThat("Virtual threads should have lower memory usage", 
        virtualResult.getAvgMemoryUsed(), lessThan(platformResult.getAvgMemoryUsed()));
    
    // Assert that both thread types have 100% success rate
    assertThat("Platform threads should have 100% success rate", 
        platformResult.getAvgSuccessRate(), is(100.0));
    assertThat("Virtual threads should have 100% success rate", 
        virtualResult.getAvgSuccessRate(), is(100.0));
  }

  /**
   * Represents the result of a single concurrency test.
   * This class encapsulates all the performance metrics collected during a test run,
   * providing a comprehensive view of the test's performance characteristics.
   */
  private static class TestResult {
    private final int concurrency;
    private final boolean useVirtualThreads;
    private final long totalTime;
    private final double throughput;
    private final double avgResponseTime;
    private final int successCount;
    private final int errorCount;
    private final long memoryUsed;

    public TestResult(int concurrency, boolean useVirtualThreads, long totalTime, double throughput, 
                     double avgResponseTime, int successCount, int errorCount, long memoryUsed) {
      this.concurrency = concurrency;
      this.useVirtualThreads = useVirtualThreads;
      this.totalTime = totalTime;
      this.throughput = throughput;
      this.avgResponseTime = avgResponseTime;
      this.successCount = successCount;
      this.errorCount = errorCount;
      this.memoryUsed = memoryUsed;
    }

    public int getConcurrency() {
      return concurrency;
    }

    public boolean isUseVirtualThreads() {
      return useVirtualThreads;
    }

    public long getTotalTime() {
      return totalTime;
    }

    public double getThroughput() {
      return throughput;
    }

    public double getAvgResponseTime() {
      return avgResponseTime;
    }

    public int getSuccessCount() {
      return successCount;
    }

    public int getErrorCount() {
      return errorCount;
    }

    public long getMemoryUsed() {
      return memoryUsed;
    }

    public double getSuccessRate() {
      return (double) successCount / (successCount + errorCount) * 100;
    }
  }

  /**
   * Aggregates multiple test results and calculates average metrics.
   * This class collects results from multiple test iterations and provides methods
   * to calculate average performance metrics, ensuring that the test results are
   * statistically significant and not affected by transient system conditions.
   */
  private static class PerformanceResult {
    private final List<TestResult> results = new ArrayList<>();

    public void addResult(TestResult result) {
      results.add(result);
    }

    public double getAvgTotalTime() {
      return results.stream().mapToLong(TestResult::getTotalTime).average().orElse(0);
    }

    public double getAvgThroughput() {
      return results.stream().mapToDouble(TestResult::getThroughput).average().orElse(0);
    }

    public double getAvgResponseTime() {
      return results.stream().mapToDouble(TestResult::getAvgResponseTime).average().orElse(0);
    }

    public double getAvgSuccessRate() {
      return results.stream().mapToDouble(TestResult::getSuccessRate).average().orElse(0);
    }

    public long getAvgMemoryUsed() {
      return (long) results.stream().mapToLong(TestResult::getMemoryUsed).average().orElse(0);
    }
  }
}