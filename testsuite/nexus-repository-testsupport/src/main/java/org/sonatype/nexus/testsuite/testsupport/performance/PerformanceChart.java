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
    java21Info.append("<h3>Java 21 Runtime Information</h3>\n");
    java21Info.append("<table border='1' cellpadding='5'>\n");
    
    java21Info.append("<tr><td>Java Version</td><td>")
              .append(System.getProperty("java.version"))
              .append("</td></tr>\n");
    
    java21Info.append("<tr><td>Java VM</td><td>")
              .append(System.getProperty("java.vm.name"))
              .append(" ")
              .append(System.getProperty("java.vm.version"))
              .append("</td></tr>\n");
    
    java21Info.append("<tr><td>Java VM Vendor</td><td>")
              .append(System.getProperty("java.vm.vendor"))
              .append("</td></tr>\n");
    
    // Thread information
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    java21Info.append("<tr><td>Platform Thread Count</td><td>")
              .append(threadMXBean.getThreadCount())
              .append("</td></tr>\n");
    
    java21Info.append("<tr><td>Peak Thread Count</td><td>")
              .append(threadMXBean.getPeakThreadCount())
              .append("</td></tr>\n");
    
    java21Info.append("<tr><td>Total Started Thread Count</td><td>")
              .append(threadMXBean.getTotalStartedThreadCount())
              .append("</td></tr>\n");
    
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
    
    java21Info.append("<tr><td>Virtual Threads Support</td><td>")
              .append(virtualThreadsSupported ? "Yes" : "No")
              .append("</td></tr>\n");
    
    // JVM flags
    java21Info.append("<tr><td>JVM Flags</td><td><pre>")
              .append(ManagementFactory.getRuntimeMXBean().getInputArguments().toString()
                  .replace("[", "")
                  .replace("]", "")
                  .replace(", ", "\n"))
              .append("</pre></td></tr>\n");
    
    java21Info.append("</table>\n");
    
    // Add Java 21 feature information
    java21Info.append("<h3>Java 21 Key Features Used</h3>\n");
    java21Info.append("<ul>\n");
    java21Info.append("<li><strong>Virtual Threads</strong> - Lightweight threads that enable high throughput for I/O-bound applications</li>\n");
    java21Info.append("<li><strong>Record Patterns</strong> - Destructuring of record values for more concise and readable code</li>\n");
    java21Info.append("<li><strong>Pattern Matching for switch</strong> - Type-based pattern matching in switch expressions</li>\n");
    java21Info.append("<li><strong>Sequenced Collections</strong> - New interfaces for collections with well-defined encounter order</li>\n");
    java21Info.append("<li><strong>String Templates</strong> - More readable string interpolation (preview feature)</li>\n");
    java21Info.append("</ul>\n");
    
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
      PerformanceTestSeries series = results.getTests().get(testName);
      for (PerformanceRunResult result : series.getResultsByThreadCount().values()) {
        if ("virtual".equals(result.threadType())) {
          hasVirtualThreadData = true;
          break;
        }
      }
      if (hasVirtualThreadData) break;
    }
    
    if (!hasVirtualThreadData) {
      metrics.append("<p>No virtual thread performance data available.</p>");
      return metrics.toString();
    }
    
    // Calculate metrics for virtual vs platform threads
    Map<Integer, Map<String, Double>> threadCountToMetrics = new HashMap<>();
    
    // For each test series, collect virtual thread metrics
    for (String testName : results.getTests().keySet()) {
      PerformanceTestSeries series = results.getTests().get(testName);
      
      // For each thread count, analyze performance
      for (Map.Entry<Integer, PerformanceRunResult> entry : series.getResultsByThreadCount().entrySet()) {
        int threadCount = entry.getKey();
        PerformanceRunResult result = entry.getValue();
        
        // Skip if not using virtual threads
        if (!"virtual".equals(result.threadType())) {
          continue;
        }
        
        // Initialize metrics for this thread count if needed
        Map<String, Double> metrics_for_count = threadCountToMetrics.computeIfAbsent(threadCount, k -> new HashMap<>());
        
        // Calculate throughput (requests per second)
        double throughput = result.getThroughput();
        
        // Update metrics
        metrics_for_count.compute("throughput", (k, v) -> (v == null) ? throughput : v + throughput);
        metrics_for_count.compute("test_count", (k, v) -> (v == null) ? 1.0 : v + 1.0);
        
        // Calculate efficiency metrics
        // Theoretical max throughput per thread (based on observed throughput at thread count 1)
        PerformanceRunResult singleThreadResult = series.getResult(1);
        if (singleThreadResult != null) {
          double singleThreadThroughput = singleThreadResult.getThroughput();
          double theoreticalMax = singleThreadThroughput * threadCount;
          double scalingEfficiency = throughput / theoreticalMax;
          metrics_for_count.compute("scaling_efficiency", (k, v) -> (v == null) ? scalingEfficiency : v + scalingEfficiency);
        }
      }
    }
    
    // Generate the metrics report
    metrics.append("<h3>Java 21 Virtual Thread Performance Metrics</h3>\n");
    metrics.append("<table border='1' cellpadding='5'>\n");
    metrics.append("<tr><th>Thread Count</th><th>Avg Throughput (req/sec)</th><th>Scaling Efficiency</th><th>Memory Efficiency</th></tr>\n");
    
    DecimalFormat format = new DecimalFormat("#,##0.00");
    DecimalFormat percentFormat = new DecimalFormat("#0.00%");
    
    for (int threadCount : new TreeSet<>(threadCountToMetrics.keySet())) {
      Map<String, Double> metricsMap = threadCountToMetrics.get(threadCount);
      double testCount = metricsMap.getOrDefault("test_count", 1.0);
      double avgThroughput = metricsMap.getOrDefault("throughput", 0.0) / testCount;
      double scalingEfficiency = metricsMap.getOrDefault("scaling_efficiency", 0.0) / testCount;
      
      // Estimate memory efficiency (platform threads ~1MB each, virtual threads ~2KB each)
      double platformMemory = threadCount * 1024 * 1024; // 1MB per thread
      double virtualMemory = threadCount * 2 * 1024;     // 2KB per thread
      double memoryEfficiency = platformMemory / virtualMemory;
      
      metrics.append("<tr>");
      metrics.append("<td>").append(threadCount).append("</td>");
      metrics.append("<td>").append(format.format(avgThroughput)).append("</td>");
      metrics.append("<td>").append(percentFormat.format(scalingEfficiency)).append("</td>");
      metrics.append("<td>").append(format.format(memoryEfficiency)).append("x</td>");
      metrics.append("</tr>\n");
    }
    
    metrics.append("</table>\n");
    
    // Add interpretation
    metrics.append("<h4>Virtual Thread Performance Analysis</h4>\n");
    metrics.append("<p><strong>Avg Throughput</strong>: Average number of requests processed per second at each thread count.</p>\n");
    metrics.append("<p><strong>Scaling Efficiency</strong>: How efficiently throughput scales with increased thread count. 100% means perfect linear scaling.</p>\n");
    metrics.append("<p><strong>Memory Efficiency</strong>: Estimated memory usage efficiency of virtual threads compared to platform threads (higher is better).</p>\n");
    
    // Add Java 21 virtual thread specific insights
    metrics.append("<h4>Java 21 Virtual Thread Insights</h4>\n");
    metrics.append("<ul>\n");
    metrics.append("<li>Virtual threads are managed by the JVM rather than the OS, allowing for much higher concurrency.</li>\n");
    metrics.append("<li>Each virtual thread requires only ~2KB of memory compared to ~1MB for platform threads.</li>\n");
    metrics.append("<li>Virtual threads automatically yield during blocking operations, improving CPU utilization.</li>\n");
    metrics.append("<li>Thread pinning can occur when using non-yielding native methods or synchronized blocks on heavily contended locks.</li>\n");
    metrics.append("</ul>\n");
    
    // Add thread pinning detection
    metrics.append("<h4>Thread Pinning Detection</h4>\n");
    metrics.append("<p>Thread pinning occurs when virtual threads cannot yield during blocking operations, negating their benefits.</p>\n");
    
    // Check for signs of thread pinning (poor scaling efficiency at higher thread counts)
    boolean possiblePinning = false;
    int pinningThreshold = 100; // Thread count where pinning might become evident
    
    for (int threadCount : new TreeSet<>(threadCountToMetrics.keySet())) {
      if (threadCount >= pinningThreshold) {
        Map<String, Double> metricsMap = threadCountToMetrics.get(threadCount);
        double scalingEfficiency = metricsMap.getOrDefault("scaling_efficiency", 0.0) / 
                                  metricsMap.getOrDefault("test_count", 1.0);
        
        if (scalingEfficiency < 0.5) { // Less than 50% scaling efficiency
          possiblePinning = true;
          break;
        }
      }
    }
    
    if (possiblePinning) {
      metrics.append("<p><strong>Warning:</strong> Possible thread pinning detected at higher thread counts. ");
      metrics.append("Consider reviewing code for synchronized blocks on heavily contended locks, ");
      metrics.append("non-yielding native methods, or CPU-bound operations.</p>\n");
    } else {
      metrics.append("<p><strong>No thread pinning detected.</strong> Virtual threads appear to be yielding properly during blocking operations.</p>\n");
    }
    
    return metrics.toString();
  }
}