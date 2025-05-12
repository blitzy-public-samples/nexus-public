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
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for comparing performance between platform threads and virtual threads.
 * 
 * <p>This class provides utilities for running the same test with both platform threads and
 * virtual threads, collecting performance metrics, and generating comparison reports.</p>
 *
 * @since 3.60
 */
public class ThreadModelComparisonTest
{
  private static final Logger log = LoggerFactory.getLogger(ThreadModelComparisonTest.class);
  
  /**
   * Enum representing different thread models for testing.
   */
  public enum ThreadModel {
    PLATFORM,
    VIRTUAL
  }
  
  private final Map<ThreadModel, PerformanceData> results = new HashMap<>();
  private final String testName;
  private final File outputDirectory;
  
  /**
   * Creates a new thread model comparison test.
   * 
   * @param testName name of the test
   * @param outputDirectory directory where reports will be written
   */
  public ThreadModelComparisonTest(String testName, File outputDirectory) {
    this.testName = testName;
    this.outputDirectory = outputDirectory;
    
    // Initialize performance data for each thread model
    results.put(ThreadModel.PLATFORM, new PerformanceData());
    results.put(ThreadModel.VIRTUAL, new PerformanceData());
  }
  
  /**
   * Runs a performance test with both platform threads and virtual threads.
   * 
   * @param testOperation the operation to test
   * @param threadCounts array of thread counts to test with
   * @param iterationsPerThread number of iterations each thread should perform
   * @param warmupIterations number of warmup iterations to perform before measuring
   * @throws Exception if an error occurs during testing
   */
  public void runComparison(Callable<Void> testOperation, 
                          int[] threadCounts, 
                          int iterationsPerThread,
                          int warmupIterations) throws Exception {
    // Run tests with platform threads
    runTest(ThreadModel.PLATFORM, testOperation, threadCounts, iterationsPerThread, warmupIterations);
    
    // Run tests with virtual threads if supported
    if (VirtualThreadExecutorFactory.isVirtualThreadsSupported()) {
      runTest(ThreadModel.VIRTUAL, testOperation, threadCounts, iterationsPerThread, warmupIterations);
    } else {
      log.warn("Virtual threads not supported in this Java runtime, skipping virtual thread tests");
    }
    
    // Generate comparison report
    generateReport();
  }
  
  /**
   * Runs a test with the specified thread model.
   */
  private void runTest(ThreadModel threadModel, 
                      Callable<Void> testOperation,
                      int[] threadCounts,
                      int iterationsPerThread,
                      int warmupIterations) throws Exception {
    log.info("Running {} test with {} thread model", testName, threadModel);
    
    PerformanceData performanceData = results.get(threadModel);
    PerformanceData.PerformanceTestSeries series = performanceData.findTestResult(testName);
    
    for (int threadCount : threadCounts) {
      log.info("Testing with {} threads", threadCount);
      
      // Create appropriate executor based on thread model
      ExecutorService executor = createExecutor(threadModel, threadCount);
      
      try {
        // Warmup
        if (warmupIterations > 0) {
          log.info("Performing {} warmup iterations", warmupIterations);
          runIterations(executor, testOperation, threadCount, warmupIterations);
        }
        
        // Actual test
        log.info("Performing {} test iterations per thread", iterationsPerThread);
        long startTime = System.currentTimeMillis();
        int completedRequests = runIterations(executor, testOperation, threadCount, iterationsPerThread);
        long endTime = System.currentTimeMillis();
        
        int durationSeconds = (int) ((endTime - startTime) / 1000);
        
        // Record results
        PerformanceData.PerformanceRunResult runResult = new PerformanceData.PerformanceRunResult(
            completedRequests, 0, durationSeconds, false);
        series.addResults(threadCount, runResult);
        
        log.info("Completed {} requests in {} seconds ({} requests/second)",
            completedRequests, durationSeconds, completedRequests / (double) durationSeconds);
      } 
      finally {
        executor.shutdown();
      }
    }
  }
  
  /**
   * Creates an executor service based on the thread model.
   */
  private ExecutorService createExecutor(ThreadModel threadModel, int threadCount) {
    switch (threadModel) {
      case PLATFORM:
        return Executors.newFixedThreadPool(threadCount);
      case VIRTUAL:
        return VirtualThreadExecutorFactory.createExecutor(threadCount);
      default:
        throw new IllegalArgumentException("Unknown thread model: " + threadModel);
    }
  }
  
  /**
   * Runs the specified number of iterations of the test operation.
   */
  private int runIterations(ExecutorService executor, 
                           Callable<Void> testOperation,
                           int threadCount,
                           int iterationsPerThread) throws Exception {
    // Submit tasks
    for (int i = 0; i < threadCount * iterationsPerThread; i++) {
      executor.submit(testOperation);
    }
    
    // Wait for completion
    executor.shutdown();
    while (!executor.isTerminated()) {
      Thread.sleep(100);
    }
    
    return threadCount * iterationsPerThread;
  }
  
  /**
   * Generates a comparison report.
   */
  private void generateReport() throws IOException {
    File reportFile = new File(outputDirectory, testName + "-thread-comparison.html");
    
    if (VirtualThreadExecutorFactory.isVirtualThreadsSupported()) {
      PerformanceChart.writeThreadComparisonReport(
          results.get(ThreadModel.PLATFORM),
          results.get(ThreadModel.VIRTUAL),
          reportFile);
    } else {
      // Just write platform thread results if virtual threads aren't supported
      PerformanceChart.writePerformanceReport(results.get(ThreadModel.PLATFORM), reportFile);
    }
    
    log.info("Thread model comparison report written to {}", reportFile.getAbsolutePath());
  }
  
  /**
   * Gets the performance data for the specified thread model.
   * 
   * @param threadModel the thread model
   * @return performance data for that thread model
   */
  public PerformanceData getResults(ThreadModel threadModel) {
    return results.get(threadModel);
  }
}