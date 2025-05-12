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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceRunResult;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceTestSeries;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests {@link PerformanceChart}
 * 
 * This test class demonstrates performance chart generation capabilities, including
 * comparison between platform threads and virtual threads introduced in Java 21.
 */
public class PerformanceChartTest
    extends TestSupport
{
  private Path chartDir;
  private Path chartFile;

  @BeforeEach
  public void setChartFileLocation(TestInfo testInfo) throws IOException {
    // Use modern Java 21 Path API instead of legacy File API
    chartDir = util.resolveFile("target/test-tmp/" + getClass().getSimpleName()).toPath();
    Files.createDirectories(chartDir);
    chartFile = chartDir.resolve(testInfo.getTestMethod().orElseThrow().getName() + ".html");
  }

  @Test
  @DisplayName("Basic chart generation smoke test")
  void writeChartSmokeTest() throws Exception {
    final PerformanceData perfData = new PerformanceData();
    final PerformanceTestSeries data = perfData.findTestResult("sample");
    data.addResults(1, new PerformanceRunResult(1, 0, 60, true));
    data.addResults(2, new PerformanceRunResult(10, 0, 60, true));
    data.addResults(3, new PerformanceRunResult(100, 0, 60, true));

    PerformanceChart.writePerformanceReport(perfData, chartFile.toFile());
    
    // Verify the chart file was created
    assertThat(Files.exists(chartFile), is(true));
  }
  
  @Test
  @Tag("java21-tests")
  @DisplayName("Compare platform threads vs virtual threads performance")
  void compareThreadModelsPerformance() throws Exception {
    // Configure thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Test with both thread types
    Map<String, PerformanceData> results = new HashMap<>();
    
    // Create performance data for virtual threads
    results.put("virtual-threads", createPerformanceData("Virtual Threads", virtualThreadFactory));
    
    // Create performance data for platform threads
    results.put("platform-threads", createPerformanceData("Platform Threads", platformThreadFactory));
    
    // Generate comparison chart for each thread model
    for (Map.Entry<String, PerformanceData> entry : results.entrySet()) {
      Path threadModelChartFile = chartDir.resolve(entry.getKey() + ".html");
      PerformanceChart.writePerformanceReport(entry.getValue(), threadModelChartFile.toFile());
      assertThat(Files.exists(threadModelChartFile), is(true));
    }
    
    // Generate a combined chart with both thread models for comparison
    PerformanceData combinedData = new PerformanceData();
    PerformanceTestSeries virtualSeries = combinedData.findTestResult("Virtual Threads");
    PerformanceTestSeries platformSeries = combinedData.findTestResult("Platform Threads");
    
    // Add sample data points (in a real test, these would be actual measurements)
    for (int i = 1; i <= 10; i++) {
      int threadCount = i * 100;
      // Virtual threads typically perform better with higher concurrency
      int virtualThroughput = 1000 + (i * 100);
      int platformThroughput = 1000 + (i * 50);
      
      virtualSeries.addResults(threadCount, 
          new PerformanceRunResult(virtualThroughput, 0, 60, false));
      platformSeries.addResults(threadCount, 
          new PerformanceRunResult(platformThroughput, 0, 60, false));
    }
    
    Path comparisonChartFile = chartDir.resolve("thread-model-comparison.html");
    PerformanceChart.writePerformanceReport(combinedData, comparisonChartFile.toFile());
    assertThat(Files.exists(comparisonChartFile), is(true));
  }
  
  @Test
  @Tag("virtual-threads")
  @DisplayName("Test virtual thread performance with high concurrency")
  void virtualThreadHighConcurrencyTest() throws Exception {
    // This test demonstrates how to test with a high number of virtual threads
    // which is a key benefit of Java 21's virtual thread implementation
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create performance data for a high concurrency scenario
      PerformanceData perfData = new PerformanceData();
      PerformanceTestSeries series = perfData.findTestResult("High Concurrency Virtual Threads");
      
      // Simulate results for different thread counts
      // In a real test, these would be measured from actual operations
      for (int i = 1; i <= 10; i++) {
        int threadCount = i * 1000; // Test with up to 10,000 virtual threads
        series.addResults(threadCount, new PerformanceRunResult(threadCount * 10, 0, 60, false));
      }
      
      // Generate the performance chart
      Path highConcurrencyChartFile = chartDir.resolve("high-concurrency-virtual-threads.html");
      PerformanceChart.writePerformanceReport(perfData, highConcurrencyChartFile.toFile());
      assertThat(Files.exists(highConcurrencyChartFile), is(true));
      
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Creates performance data for the specified thread model.
   * 
   * @param label the label for the performance data series
   * @param threadFactory the thread factory to use (virtual or platform)
   * @return performance data with simulated results
   */
  private PerformanceData createPerformanceData(String label, ThreadFactory threadFactory) {
    PerformanceData perfData = new PerformanceData();
    PerformanceTestSeries series = perfData.findTestResult(label);
    
    // In a real test, we would run actual operations with the thread factory
    // and measure the results. Here we're just simulating the data.
    for (int i = 1; i <= 5; i++) {
      int threadCount = i * 10;
      int throughput = threadCount * 10;
      
      // If using virtual threads, simulate better scaling at higher thread counts
      if (threadFactory == Thread.ofVirtual().factory()) {
        throughput += (threadCount * 2);
      }
      
      series.addResults(threadCount, new PerformanceRunResult(throughput, 0, 60, false));
    }
    
    return perfData;
  }
}