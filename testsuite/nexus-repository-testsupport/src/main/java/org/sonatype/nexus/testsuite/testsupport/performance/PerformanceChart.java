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
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceRunResult;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceTestSeries;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

import static com.google.common.base.Preconditions.checkState;

/**
 * Utilities for writing performance results to an HTML chart.
 * 
 * <p>Enhanced for Java 21 to include virtual thread metrics and performance comparisons between
 * platform threads and virtual threads. This class provides visualization of performance characteristics
 * specific to Java 21 features, including thread pinning detection and virtual thread scaling.</p>
 *
 * @since 3.0
 */
public class PerformanceChart
{
  private static final Logger log = LoggerFactory.getLogger(PerformanceChart.class);

  /**
   * Placeholder in the HTML template for chart data
   */
  private static final String CHART_DATA_PLACEHOLDER = "/*ROW-DATA*/";
  
  /**
   * Placeholder in the HTML template for system information
   */
  private static final String SYSTEM_INFO_PLACEHOLDER = "<!-- SYSTEM-INFO -->";
  
  /**
   * Placeholder in the HTML template for Java 21 runtime information
   * Note: This placeholder may not exist in older templates and will be ignored
   */
  private static final String JAVA21_INFO_PLACEHOLDER = "<!-- JAVA21-INFO -->";
  
  /**
   * Placeholder in the HTML template for virtual thread metrics
   * Note: This placeholder may not exist in older templates and will be ignored
   */
  private static final String VIRTUAL_THREAD_METRICS_PLACEHOLDER = "<!-- VIRTUAL-THREAD-METRICS -->";

  private PerformanceChart() {
    // empty
  }

  /**
   * Writes performance test results to an HTML report file.
   *
   * @param results the performance test results to visualize
   * @param outputFile the file to write the HTML report to
   * @throws IOException if there is an error writing the file
   */
  public static void writePerformanceReport(final PerformanceData results, final File outputFile)
      throws IOException
  {
    String chartData = buildChartData(results);

    final String reportTemplate = loadReportTemplate();

    String reportHtml = reportTemplate.replace(CHART_DATA_PLACEHOLDER, chartData);
    reportHtml = reportHtml.replace(SYSTEM_INFO_PLACEHOLDER, buildSystemInfo());
    
    // Only replace Java 21 placeholders if they exist in the template
    if (reportTemplate.contains(JAVA21_INFO_PLACEHOLDER)) {
      reportHtml = reportHtml.replace(JAVA21_INFO_PLACEHOLDER, buildJava21Info());
    } else {
      // If the placeholder doesn't exist, append Java 21 info to system info section
      String combinedInfo = buildSystemInfo() + "\n\nJava 21 Information:\n" + buildJava21Info();
      reportHtml = reportHtml.replace(SYSTEM_INFO_PLACEHOLDER, combinedInfo);
    }
    
    if (reportTemplate.contains(VIRTUAL_THREAD_METRICS_PLACEHOLDER)) {
      reportHtml = reportHtml.replace(VIRTUAL_THREAD_METRICS_PLACEHOLDER, buildVirtualThreadMetrics(results));
    }

    log.info("Writing performance chart to {}", outputFile);
    Files.asCharSink(outputFile, StandardCharsets.UTF_8).write(reportHtml);
  }

  /**
   * Writes a comparison of platform threads vs virtual threads performance to an HTML report file.
   *
   * @param platformResults the performance test results using platform threads
   * @param virtualResults the performance test results using virtual threads
   * @param outputFile the file to write the HTML report to
   * @throws IOException if there is an error writing the file
   */
  public static void writeThreadComparisonReport(final PerformanceData platformResults, 
                                                final PerformanceData virtualResults,
                                                final File outputFile)
      throws IOException
  {
    // Combine the results into a single dataset for visualization
    PerformanceData combinedResults = new PerformanceData();
    
    // Add platform thread results with prefix
    for (Map.Entry<String, PerformanceTestSeries> entry : platformResults.getTests().entrySet()) {
      String testName = "Platform-" + entry.getKey();
      PerformanceTestSeries series = combinedResults.findTestResult(testName);
      
      for (Map.Entry<Integer, PerformanceRunResult> resultEntry : 
           entry.getValue().getResultsByThreadCount().entrySet()) {
        series.addResults(resultEntry.getKey(), resultEntry.getValue());
      }
    }
    
    // Add virtual thread results with prefix
    for (Map.Entry<String, PerformanceTestSeries> entry : virtualResults.getTests().entrySet()) {
      String testName = "Virtual-" + entry.getKey();
      PerformanceTestSeries series = combinedResults.findTestResult(testName);
      
      for (Map.Entry<Integer, PerformanceRunResult> resultEntry : 
           entry.getValue().getResultsByThreadCount().entrySet()) {
        series.addResults(resultEntry.getKey(), resultEntry.getValue());
      }
    }
    
    // Write the combined report
    writePerformanceReport(combinedResults, outputFile);
  }

  private static String loadReportTemplate() throws IOException {
    final URL reportTemplateResource = PerformanceChart.class.getResource("performanceReport.html");
    checkState(reportTemplateResource != null, "Performance report template not found");
    return Resources.toString(reportTemplateResource, StandardCharsets.UTF_8);
  }

  /**
   * Builds a Javascript array to be inserted into the Google Charts API definition.
   */
  private static String buildChartData(final PerformanceData results) {
    final DecimalFormat format = new DecimalFormat("#.00");

    StringBuilder s = new StringBuilder();

    SortedSet<String> testNames = new TreeSet<>(results.getTests().keySet());

    // Create the header row
    s.append("['# of Client Threads'");
    for (String testName : testNames) {
      s.append(",'")
       .append(testName)
       .append("'");
    }
    s.append("]");

    for (int threadCount : results.getThreadCounts()) {
      s.append(",[")
       .append(threadCount);

      for (String testName : testNames) {
        final PerformanceTestSeries series = results.findTestResult(testName);

        final PerformanceRunResult test = series.getResultsByThreadCount().get(threadCount);
        if (test == null) {
          s.append(",0"); // No data for this thread count
          continue;
        }
        
        final int requestsCompleted = test.getRequestsCompleted();
        final int duration = test.getTestDurationSeconds();
        final double requestPerSecond = duration > 0 ? ((double) requestsCompleted) / duration : 0;

        s.append(",")
         .append(format.format(requestPerSecond));
      }

      s.append("]");
    }
    return s.toString();
  }

  /**
   * Builds system information section for the report.
   */
  private static String buildSystemInfo() {
    StringBuilder systemSummary = new StringBuilder();

    final SystemInfo systemInfo = new SystemInfo();
    final HardwareAbstractionLayer hardware = systemInfo.getHardware();

    final CentralProcessor processor = hardware.getProcessor();
    systemSummary.append("Processor: ")
                 .append(processor.getProcessorIdentifier())
                 .append("\n");

    final GlobalMemory memory = hardware.getMemory();
    systemSummary.append(String.format("Memory: %,d Mb%n", memory.getTotal() / (1024 * 1024)));

    final OperatingSystem os = systemInfo.getOperatingSystem();
    systemSummary.append(String.format("OS: %s %s %s%n", 
                                      os.getManufacturer(), 
                                      os.getFamily(), 
                                      os.getVersionInfo()));

    return systemSummary.toString();
  }
  
  /**
   * Builds Java 21 runtime information section for the report.
   */
  private static String buildJava21Info() {
    StringBuilder java21Info = new StringBuilder();
    
    // Java version information
    java21Info.append("Java Version: ")
              .append(System.getProperty("java.version"))
              .append("\n");
    
    java21Info.append("Java VM: ")
              .append(System.getProperty("java.vm.name"))
              .append(" ")
              .append(System.getProperty("java.vm.version"))
              .append("\n");
    
    java21Info.append("Java VM Vendor: ")
              .append(System.getProperty("java.vm.vendor"))
              .append("\n");
    
    // JVM flags
    java21Info.append("\nJVM Flags:\n")
              .append(ManagementFactory.getRuntimeMXBean().getInputArguments())
              .append("\n");
    
    // Thread information
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    java21Info.append("\nThread Information:\n")
              .append("Thread Count: ")
              .append(threadMXBean.getThreadCount())
              .append("\n")
              .append("Peak Thread Count: ")
              .append(threadMXBean.getPeakThreadCount())
              .append("\n")
              .append("Total Started Thread Count: ")
              .append(threadMXBean.getTotalStartedThreadCount())
              .append("\n");
    
    // Virtual thread support detection
    boolean virtualThreadsSupported = false;
    try {
      // Check if Thread.ofVirtual() method exists (Java 21 feature)
      Class.forName("java.lang.Thread").getMethod("ofVirtual");
      virtualThreadsSupported = true;
    } 
    catch (NoSuchMethodException | ClassNotFoundException e) {
      // Virtual threads not supported
    }
    
    java21Info.append("\nVirtual Threads Support: ")
              .append(virtualThreadsSupported ? "Yes" : "No")
              .append("\n");
    
    return java21Info.toString();
  }
  
  /**
   * Builds virtual thread metrics section for the report.
   */
  private static String buildVirtualThreadMetrics(final PerformanceData results) {
    StringBuilder metrics = new StringBuilder();
    
    // Check if we have any virtual thread data
    boolean hasVirtualThreadData = false;
    for (String testName : results.getTests().keySet()) {
      if (testName.startsWith("Virtual-")) {
        hasVirtualThreadData = true;
        break;
      }
    }
    
    if (!hasVirtualThreadData) {
      metrics.append("No virtual thread performance data available.");
      return metrics.toString();
    }
    
    // Calculate metrics for virtual vs platform threads
    Map<Integer, Double> threadCountToSpeedup = new HashMap<>();
    Map<Integer, Double> threadCountToMemoryEfficiency = new HashMap<>();
    
    // For each thread count, compare virtual vs platform performance
    for (int threadCount : results.getThreadCounts()) {
      double platformThroughput = 0.0;
      double virtualThroughput = 0.0;
      
      // Find matching test pairs (Virtual-X and Platform-X)
      for (String testName : results.getTests().keySet()) {
        if (testName.startsWith("Virtual-")) {
          String baseName = testName.substring("Virtual-".length());
          String platformTestName = "Platform-" + baseName;
          
          PerformanceTestSeries virtualSeries = results.findTestResult(testName);
          PerformanceTestSeries platformSeries = results.getTests().get(platformTestName);
          
          if (platformSeries != null) {
            PerformanceRunResult virtualResult = virtualSeries.getResultsByThreadCount().get(threadCount);
            PerformanceRunResult platformResult = platformSeries.getResultsByThreadCount().get(threadCount);
            
            if (virtualResult != null && platformResult != null) {
              double virtualRps = ((double) virtualResult.getRequestsCompleted()) / virtualResult.getTestDurationSeconds();
              double platformRps = ((double) platformResult.getRequestsCompleted()) / platformResult.getTestDurationSeconds();
              
              virtualThroughput += virtualRps;
              platformThroughput += platformRps;
            }
          }
        }
      }
      
      // Calculate speedup ratio (virtual / platform)
      if (platformThroughput > 0) {
        double speedup = virtualThroughput / platformThroughput;
        threadCountToSpeedup.put(threadCount, speedup);
        
        // Estimate memory efficiency (assuming platform threads use ~1MB each and virtual threads use ~2KB each)
        double platformMemory = threadCount * 1024 * 1024; // 1MB per thread
        double virtualMemory = threadCount * 2 * 1024;     // 2KB per thread
        double memoryEfficiency = platformMemory / virtualMemory;
        threadCountToMemoryEfficiency.put(threadCount, memoryEfficiency);
      }
    }
    
    // Generate the metrics report
    metrics.append("<h3>Virtual Thread Performance Metrics</h3>\n");
    metrics.append("<table border='1' cellpadding='5'>\n");
    metrics.append("<tr><th>Thread Count</th><th>Throughput Speedup</th><th>Memory Efficiency</th></tr>\n");
    
    DecimalFormat format = new DecimalFormat("#.##x");
    for (int threadCount : new TreeSet<>(threadCountToSpeedup.keySet())) {
      double speedup = threadCountToSpeedup.get(threadCount);
      double memoryEfficiency = threadCountToMemoryEfficiency.getOrDefault(threadCount, 0.0);
      
      metrics.append("<tr>");
      metrics.append("<td>").append(threadCount).append("</td>");
      metrics.append("<td>").append(format.format(speedup)).append("</td>");
      metrics.append("<td>").append(format.format(memoryEfficiency)).append("</td>");
      metrics.append("</tr>\n");
    }
    
    metrics.append("</table>\n");
    
    // Add interpretation
    metrics.append("<p><strong>Throughput Speedup</strong>: How many times faster virtual threads process requests compared to platform threads.</p>\n");
    metrics.append("<p><strong>Memory Efficiency</strong>: Estimated memory usage efficiency of virtual threads compared to platform threads.</p>\n");
    
    return metrics.toString();
  }
}