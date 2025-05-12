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
package org.sonatype.nexus.testsuite.testsupport.performance;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.testsupport.TestIndex;
import org.sonatype.nexus.common.io.DirectoryHelper;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceRunResult;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceTestSeries;

import com.google.common.util.concurrent.Runnables;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Conducts a performance test with a variable number of clients, contributing a data series to the format performance
 * chart.
 *
 * <p>This implementation leverages Java 21 Virtual Threads for improved concurrency and
 * resource utilization during performance testing. Virtual threads are lightweight threads
 * that are managed by the JVM rather than the operating system, allowing for much higher
 * concurrency with minimal overhead.</p>
 *
 * <p>Key benefits of using virtual threads for performance testing:</p>
 * <ul>
 *   <li>Higher concurrency - Can simulate thousands of concurrent clients with minimal resources</li>
 *   <li>Improved resource utilization - Virtual threads automatically yield during blocking operations</li>
 *   <li>More realistic testing - Can test with thread counts that better represent production loads</li>
 *   <li>Simplified code - No need for complex asynchronous programming models</li>
 * </ul>
 *
 * <p>This class also provides comparison capabilities between platform threads and virtual threads
 * to help quantify the performance benefits of Java 21 virtual threads.</p>
 *
 * @since 3.0
 */
public class EscalatingClientLoadExecutor
    extends ComponentSupport
{
  /**
   * The numbers of threads used to conduct the performance tests.
   *
   * <p>With Java 21 virtual threads, we can efficiently handle higher concurrency levels
   * than with platform threads. Virtual threads have much lower overhead, allowing tests
   * with higher thread counts to better simulate real-world load scenarios.</p>
   * 
   * <p>The expanded thread counts (100, 250, 500) are practical with virtual threads
   * but would be resource-intensive with platform threads. This allows testing scalability
   * at levels that would be impractical with traditional threading models.</p>
   */
  private static final int[] THREAD_COUNTS = new int[]{1, 10, 25, 50, 100, 250, 500};

  /**
   * How long should the run be for each data point?
   */
  public static final int DURATION_SECONDS = 60;

  /**
   * Flag to enable virtual thread analytics in performance reports.
   * When enabled, additional tests will be run using platform threads for comparison.
   */
  private static final boolean ENABLE_VIRTUAL_THREAD_ANALYTICS = true;

  private final TestIndex testIndex;

  private final Runnable before;

  /**
   * Accepts an optional callable to be invoked between loads with different numbers of clients.
   */
  public EscalatingClientLoadExecutor(final TestIndex testIndex, @Nullable final Runnable before) {
    this.testIndex = checkNotNull(testIndex);
    this.before = before != null ? before : Runnables.doNothing();
  }

  /**
   * Executes performance tests with an escalating number of virtual threads and generates performance reports.
   * 
   * <p>This method leverages Java 21 virtual threads to efficiently handle concurrent requests,
   * providing better scalability and resource utilization compared to platform threads.</p>
   *
   * @param dataSeriesName the name of the data series for reporting
   * @param tasks the tasks to execute during the performance test
   * @param reportDir the directory where performance reports will be saved
   * @throws Exception if an error occurs during test execution
   */
  public void calculateAndGraphPerformance(final String dataSeriesName,
                                           final List<Callable<?>> tasks,
                                           final File reportDir)
      throws Exception
  {
    checkNotNull(reportDir);
    if (!reportDir.exists()) {
      DirectoryHelper.mkdir(reportDir);
    }

    final File dataFile = new File(reportDir, "performance-data.json");

    final PerformanceData results = PerformanceDataIO.loadTestData(dataFile);

    PerformanceTestSeries testResults = results.findTestResult(dataSeriesName);

    // Now carry out the tests, with an escalating number of virtual threads
    for (int clientThreads : THREAD_COUNTS) {

      // Do whatever preparatory step the test requires
      before.run();

      // Create a LoadExecutor that uses virtual threads for improved concurrency
      final LoadExecutor loadExec = new LoadExecutor(tasks, clientThreads, DURATION_SECONDS, true);

      boolean exceptionThrown = false;
      try {
        // Execute tasks using virtual threads
        loadExec.callTasks();
      }
      catch (Exception e) {
        log.warn("Performance run for {} with {} virtual threads aborted with exception", 
                dataSeriesName, clientThreads, e);
        exceptionThrown = true;
      }
      catch (AssertionError e) {
        log.warn("Performance run for {} with {} virtual threads failed assertion", 
                dataSeriesName, clientThreads, e);
        exceptionThrown = true;
      }

      // Record the results with virtual thread type
      testResults.addResults(clientThreads, new PerformanceRunResult(
          loadExec.getRequestsProcessed(),
          loadExec.getRequestsStarted() - loadExec.getRequestsProcessed(),
          DURATION_SECONDS,
          exceptionThrown,
          "virtual"));

      PerformanceDataIO.saveTestData(results, dataFile);
      testIndex.recordLink("performance-data", dataFile);
    }

    // Generate standard performance report
    final File htmlReport = new File(reportDir, "performance-report.html");
    PerformanceChart.writePerformanceReport(results, htmlReport);
    testIndex.recordLink("performance-report", htmlReport);
    
    // If enabled, run a comparison test with platform threads vs virtual threads
    if (ENABLE_VIRTUAL_THREAD_ANALYTICS) {
      generateVirtualThreadComparisonReport(dataSeriesName, tasks, reportDir, results);
    }
  }
  
  /**
   * Generates a comparison report between platform threads and virtual threads.
   * This helps visualize the performance benefits of Java 21 virtual threads.
   *
   * <p>This method runs the same performance tests using platform threads instead of virtual threads,
   * allowing for direct comparison of performance characteristics. The comparison report includes
   * metrics such as throughput, scaling efficiency, and resource utilization.</p>
   *
   * <p>Note: Platform thread tests are limited to lower thread counts (1, 10, 25, 50) to avoid
   * excessive resource consumption, while virtual thread tests can use much higher thread counts.</p>
   *
   * @param dataSeriesName the name of the data series for reporting
   * @param tasks the tasks to execute during the performance test
   * @param reportDir the directory where performance reports will be saved
   * @param virtualThreadResults the results from virtual thread tests
   * @throws Exception if an error occurs during test execution
   */
  private void generateVirtualThreadComparisonReport(final String dataSeriesName,
                                                    final List<Callable<?>> tasks,
                                                    final File reportDir,
                                                    final PerformanceData virtualThreadResults)
      throws Exception
  {
    log.info("Generating platform vs virtual thread comparison for {}", dataSeriesName);
    
    // Create a separate data file for platform thread results
    final File platformDataFile = new File(reportDir, "platform-thread-data.json");
    final PerformanceData platformResults = PerformanceDataIO.loadTestData(platformDataFile);
    
    // Use a subset of thread counts for platform thread testing to avoid excessive resource usage
    int[] platformThreadCounts = new int[]{1, 10, 25, 50};
    
    PerformanceTestSeries platformTestResults = platformResults.findTestResult(dataSeriesName);
    
    // Run tests with platform threads for comparison
    for (int clientThreads : platformThreadCounts) {
      // Do whatever preparatory step the test requires
      before.run();
      
      // Create a LoadExecutor that uses platform threads instead of virtual threads
      // We need to use platform threads for comparison with virtual threads
      final LoadExecutor loadExec = new LoadExecutor(tasks, clientThreads, DURATION_SECONDS, false);
      
      boolean exceptionThrown = false;
      try {
        loadExec.callTasks();
      }
      catch (Exception | AssertionError e) {
        log.warn("Platform thread performance run for {} with {} threads failed", 
                dataSeriesName, clientThreads, e);
        exceptionThrown = true;
      }
      
      // Record the results with platform thread type
      platformTestResults.addResults(clientThreads, new PerformanceRunResult(
          loadExec.getRequestsProcessed(),
          loadExec.getRequestsStarted() - loadExec.getRequestsProcessed(),
          DURATION_SECONDS,
          exceptionThrown,
          "platform"));
      
      PerformanceDataIO.saveTestData(platformResults, platformDataFile);
    }
    
    // Generate comparison report
    final File comparisonReport = new File(reportDir, "thread-comparison-report.html");
    PerformanceChart.writeThreadComparisonReport(platformResults, virtualThreadResults, comparisonReport);
    testIndex.recordLink("thread-comparison-report", comparisonReport);
    
    log.info("Thread comparison report generated at {}", comparisonReport);
  }
}
