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
package org.sonatype.nexus.pax.logging;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Benchmarks the performance of Java 21 String Templates in the Nexus logging infrastructure
 * compared to traditional string concatenation and formatting methods.
 * <p>
 * This test measures execution time, memory usage, and throughput to ensure that the adoption
 * of String Templates maintains or improves logging performance. The benchmark evaluates different
 * template complexities to provide a comprehensive performance analysis across various logging scenarios.
 * <p>
 * Key metrics measured:
 * <ul>
 *   <li>Throughput (operations/second) - Higher is better</li>
 *   <li>Average execution time (microseconds) - Lower is better</li>
 *   <li>Memory usage (heap and non-heap) - Lower is better</li>
 * </ul>
 * <p>
 * Run with: java --enable-preview -jar benchmarks.jar StringTemplatePerformanceTest
 *
 * @since 3.60
 */
/**
 * JMH benchmark configuration:
 * - Measures both throughput (ops/time) and average execution time
 * - Reports results in microseconds for precise comparison
 * - Performs 5 warmup iterations to ensure JVM optimization stability
 * - Runs 5 measurement iterations for statistical significance
 * - Uses a single fork to reduce benchmark execution time
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class StringTemplatePerformanceTest
{
  /**
   * State class that provides test data with varying complexity levels.
   * <p>
   * This class simulates different logging scenarios with increasing complexity:
   * <ul>
   *   <li>SIMPLE: Basic logging with class name, method name, and a message</li>
   *   <li>MEDIUM: Adds status code, request ID, and timestamp information</li>
   *   <li>COMPLEX: Adds user information, session details, IP address, and resource information</li>
   * </ul>
   * <p>
   * These complexity levels help evaluate how different string formatting approaches
   * scale with increasing template complexity and variable count.
   */
  @State(Scope.Benchmark)
  public static class LoggingState
  {
    @Param({"SIMPLE", "MEDIUM", "COMPLEX"})
    private ComplexityLevel complexityLevel;

    // Simple logging variables
    public String message;
    public String className;
    public String methodName;
    
    // Medium complexity variables
    public int statusCode;
    public String requestId;
    public long timestamp;
    
    // Complex logging variables
    public String userId;
    public String sessionId;
    public String ipAddress;
    public int attemptCount;
    public String resourcePath;
    public boolean isSuccessful;

    /**
     * Sets up the test data for each complexity level.
     * <p>
     * This method initializes all variables used in the benchmark tests with realistic values
     * that simulate actual logging scenarios in the Nexus environment. The data is structured
     * to represent common logging patterns found in repository management operations.
     */
    @Setup
    public void setup() {
      // Initialize basic variables used in all complexity levels
      message = "Operation completed successfully";
      className = "RepositoryManagerImpl";
      methodName = "processRequest";
      
      // Initialize medium complexity variables
      statusCode = 200;
      requestId = UUID.randomUUID().toString();
      timestamp = System.currentTimeMillis();
      
      // Initialize complex logging variables
      userId = "admin-user";
      sessionId = UUID.randomUUID().toString().substring(0, 8);
      ipAddress = "192.168.1.1";
      attemptCount = 3;
      resourcePath = "/content/repositories/maven-central/org/sonatype/nexus/nexus-core/3.60.0/nexus-core-3.60.0.jar";
      isSuccessful = true;
    }
    
    /**
     * Enum defining the complexity levels for the benchmark tests.
     * Each level represents a different logging scenario with increasing complexity.
     */
    public enum ComplexityLevel {
      /** Basic logging with a few variables (class name, method name, message) */
      SIMPLE,
      
      /** Medium complexity with more variables (adds status code, request ID, timestamp) */
      MEDIUM,
      
      /** Complex logging with many variables and longer strings (adds user info, session details, etc.) */
      COMPLEX
    }
  }

  /**
   * Benchmark for string concatenation approach.
   * <p>
   * This method tests the performance of traditional string concatenation using the '+' operator.
   * While simple to use, this approach can create multiple intermediate String objects,
   * potentially impacting performance and memory usage in high-volume logging scenarios.
   */
  @Benchmark
  public void stringConcatenation(LoggingState state, Blackhole blackhole) {
    String result;
    
    switch (state.complexityLevel) {
      case SIMPLE:
        result = "[" + state.className + "] " + state.methodName + ": " + state.message;
        break;
        
      case MEDIUM:
        result = "[" + state.className + "] " + state.methodName + ": " + state.message + 
                " (status=" + state.statusCode + ", requestId=" + state.requestId + ", time=" + state.timestamp + ")";
        break;
        
      case COMPLEX:
        result = "[" + state.className + "] " + state.methodName + ": " + state.message + 
                " (status=" + state.statusCode + ", requestId=" + state.requestId + ", time=" + state.timestamp + ")" +
                " User: " + state.userId + " Session: " + state.sessionId + " IP: " + state.ipAddress + 
                " Attempts: " + state.attemptCount + " Resource: " + state.resourcePath + 
                " Success: " + state.isSuccessful;
        break;
        
      default:
        result = state.message;
    }
    
    blackhole.consume(result);
  }

  /**
   * Benchmark for String.format approach.
   * <p>
   * This method tests the performance of String.format(), which is commonly used in logging
   * frameworks. While it provides good readability and type safety, it can be slower than
   * other approaches due to parsing format specifiers and reflection-based argument handling.
   */
  @Benchmark
  public void stringFormat(LoggingState state, Blackhole blackhole) {
    String result;
    
    switch (state.complexityLevel) {
      case SIMPLE:
        result = String.format("[%s] %s: %s", 
            state.className, state.methodName, state.message);
        break;
        
      case MEDIUM:
        result = String.format("[%s] %s: %s (status=%d, requestId=%s, time=%d)", 
            state.className, state.methodName, state.message, 
            state.statusCode, state.requestId, state.timestamp);
        break;
        
      case COMPLEX:
        result = String.format("[%s] %s: %s (status=%d, requestId=%s, time=%d) User: %s Session: %s IP: %s Attempts: %d Resource: %s Success: %b", 
            state.className, state.methodName, state.message, 
            state.statusCode, state.requestId, state.timestamp,
            state.userId, state.sessionId, state.ipAddress, 
            state.attemptCount, state.resourcePath, state.isSuccessful);
        break;
        
      default:
        result = state.message;
    }
    
    blackhole.consume(result);
  }

  /**
   * Benchmark for StringBuilder approach.
   * <p>
   * This method tests the performance of StringBuilder, which is the recommended approach
   * for string concatenation in Java. It avoids creating intermediate String objects and
   * provides better performance for complex string building operations, especially in loops.
   */
  @Benchmark
  public void stringBuilder(LoggingState state, Blackhole blackhole) {
    StringBuilder sb = new StringBuilder();
    
    switch (state.complexityLevel) {
      case SIMPLE:
        sb.append('[').append(state.className).append("] ")
          .append(state.methodName).append(": ")
          .append(state.message);
        break;
        
      case MEDIUM:
        sb.append('[').append(state.className).append("] ")
          .append(state.methodName).append(": ")
          .append(state.message)
          .append(" (status=").append(state.statusCode)
          .append(", requestId=").append(state.requestId)
          .append(", time=").append(state.timestamp).append(')');
        break;
        
      case COMPLEX:
        sb.append('[').append(state.className).append("] ")
          .append(state.methodName).append(": ")
          .append(state.message)
          .append(" (status=").append(state.statusCode)
          .append(", requestId=").append(state.requestId)
          .append(", time=").append(state.timestamp).append(')')
          .append(" User: ").append(state.userId)
          .append(" Session: ").append(state.sessionId)
          .append(" IP: ").append(state.ipAddress)
          .append(" Attempts: ").append(state.attemptCount)
          .append(" Resource: ").append(state.resourcePath)
          .append(" Success: ").append(state.isSuccessful);
        break;
        
      default:
        sb.append(state.message);
    }
    
    blackhole.consume(sb.toString());
  }

  /**
   * Benchmark for Java 21 String Templates approach.
   * <p>
   * This method tests the performance of Java 21's String Templates feature with the STR processor.
   * String Templates provide a more readable and maintainable way to create strings with embedded
   * expressions, potentially offering performance benefits over traditional approaches.
   * <p>
   * The STR processor performs simple string interpolation by replacing each embedded expression
   * with its string representation.
   */
  @Benchmark
  public void stringTemplate(LoggingState state, Blackhole blackhole) {
    String result;
    
    switch (state.complexityLevel) {
      case SIMPLE:
        result = STR."[\{state.className}] \{state.methodName}: \{state.message}";
        break;
        
      case MEDIUM:
        result = STR."[\{state.className}] \{state.methodName}: \{state.message} (status=\{state.statusCode}, requestId=\{state.requestId}, time=\{state.timestamp})";
        break;
        
      case COMPLEX:
        result = STR."[\{state.className}] \{state.methodName}: \{state.message} (status=\{state.statusCode}, requestId=\{state.requestId}, time=\{state.timestamp}) User: \{state.userId} Session: \{state.sessionId} IP: \{state.ipAddress} Attempts: \{state.attemptCount} Resource: \{state.resourcePath} Success: \{state.isSuccessful}";
        break;
        
      default:
        result = state.message;
    }
    
    blackhole.consume(result);
  }

  /**
   * Benchmark for Java 21 String Templates with FMT processor for formatted output.
   * <p>
   * This method tests the performance of Java 21's String Templates feature with the FMT processor.
   * The FMT processor combines the functionality of String.format() with the readability of
   * String Templates, allowing for formatted output with type-specific formatting.
   * <p>
   * This approach is particularly useful for formatting numbers, dates, and other data types
   * that benefit from specific formatting rules.
   */
  @Benchmark
  public void stringTemplateFmt(LoggingState state, Blackhole blackhole) {
    String result;
    
    switch (state.complexityLevel) {
      case SIMPLE:
        result = FMT."[%s] %s: %s"{state.className, state.methodName, state.message};
        break;
        
      case MEDIUM:
        result = FMT."[%s] %s: %s (status=%d, requestId=%s, time=%d)"{state.className, state.methodName, state.message, 
                state.statusCode, state.requestId, state.timestamp};
        break;
        
      case COMPLEX:
        result = FMT."[%s] %s: %s (status=%d, requestId=%s, time=%d) User: %s Session: %s IP: %s Attempts: %d Resource: %s Success: %b"{state.className, state.methodName, state.message, 
                state.statusCode, state.requestId, state.timestamp,
                state.userId, state.sessionId, state.ipAddress, 
                state.attemptCount, state.resourcePath, state.isSuccessful};
        break;
        
      default:
        result = state.message;
    }
    
    blackhole.consume(result);
  }

  /**
   * Main method to run the benchmark from the command line.
   */
  /**
   * State class for memory usage measurements.
   * <p>
   * This class provides functionality to measure memory usage before and after benchmark execution.
   * It captures heap and non-heap memory usage to evaluate the memory efficiency of different
   * string formatting approaches.
   * <p>
   * Memory measurements are particularly important for logging operations, as excessive memory
   * usage in high-volume logging scenarios can impact application performance and stability.
   */
  @State(Scope.Thread)
  public static class MemoryState {
    private MemoryMXBean memoryMXBean;
    private long beforeHeapUsed;
    private long beforeNonHeapUsed;
    
    /**
     * Sets up the memory measurement environment before benchmark execution.
     * <p>
     * This method performs the following steps:
     * <ol>
     *   <li>Obtains the MemoryMXBean for memory usage monitoring</li>
     *   <li>Forces garbage collection to establish a clean baseline</li>
     *   <li>Records the initial heap and non-heap memory usage</li>
     * </ol>
     * <p>
     * These measurements provide a baseline for calculating memory consumption
     * during benchmark execution.
     */
    @Setup
    public void setup() {
      memoryMXBean = ManagementFactory.getMemoryMXBean();
      // Force garbage collection to get a clean baseline
      System.gc();
      MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
      MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
      beforeHeapUsed = heapUsage.getUsed();
      beforeNonHeapUsed = nonHeapUsage.getUsed();
    }
    
    /**
     * Measures and reports memory usage after benchmark execution.
     * <p>
     * This method performs the following steps:
     * <ol>
     *   <li>Forces garbage collection to ensure accurate measurements</li>
     *   <li>Records the final heap and non-heap memory usage</li>
     *   <li>Calculates the difference between initial and final memory usage</li>
     *   <li>Reports the memory consumption using String Templates</li>
     * </ol>
     * <p>
     * The reported memory differences help evaluate the memory efficiency of
     * different string formatting approaches in the benchmark.
     */
    @TearDown
    public void tearDown() {
      System.gc();
      MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
      MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
      long afterHeapUsed = heapUsage.getUsed();
      long afterNonHeapUsed = nonHeapUsage.getUsed();
      
      // Calculate memory differences
      long heapDiff = afterHeapUsed - beforeHeapUsed;
      long nonHeapDiff = afterNonHeapUsed - beforeNonHeapUsed;
      
      // Log memory usage differences using String Templates
      System.out.println(STR."Memory usage - Heap: \{heapDiff} bytes, Non-Heap: \{nonHeapDiff} bytes");
    }
  }
  
  /**
   * Benchmark for memory usage with string concatenation.
   * <p>
   * This method measures the memory consumption of the string concatenation approach.
   * String concatenation typically creates multiple intermediate String objects,
   * which can lead to higher memory usage compared to other approaches.
   */
  @Benchmark
  public void memoryStringConcatenation(LoggingState state, MemoryState memoryState, Blackhole blackhole) {
    stringConcatenation(state, blackhole);
  }
  
  /**
   * Benchmark for memory usage with String.format.
   * <p>
   * This method measures the memory consumption of the String.format approach.
   * String.format creates internal buffers and uses reflection, which can impact
   * memory usage patterns.
   */
  @Benchmark
  public void memoryStringFormat(LoggingState state, MemoryState memoryState, Blackhole blackhole) {
    stringFormat(state, blackhole);
  }
  
  /**
   * Benchmark for memory usage with StringBuilder.
   * <p>
   * This method measures the memory consumption of the StringBuilder approach.
   * StringBuilder is designed to be memory-efficient for string concatenation operations,
   * as it maintains a single buffer that grows as needed.
   */
  @Benchmark
  public void memoryStringBuilder(LoggingState state, MemoryState memoryState, Blackhole blackhole) {
    stringBuilder(state, blackhole);
  }
  
  /**
   * Benchmark for memory usage with String Templates.
   * <p>
   * This method measures the memory consumption of the Java 21 String Templates approach
   * with the STR processor. This benchmark helps evaluate whether String Templates provide
   * memory efficiency benefits compared to traditional approaches.
   */
  @Benchmark
  public void memoryStringTemplate(LoggingState state, MemoryState memoryState, Blackhole blackhole) {
    stringTemplate(state, blackhole);
  }
  
  /**
   * Benchmark for memory usage with String Templates FMT processor.
   * <p>
   * This method measures the memory consumption of the Java 21 String Templates approach
   * with the FMT processor. This benchmark helps evaluate the memory impact of using
   * formatted string templates compared to other approaches.
   */
  @Benchmark
  public void memoryStringTemplateFmt(LoggingState state, MemoryState memoryState, Blackhole blackhole) {
    stringTemplateFmt(state, blackhole);
  }

  /**
   * Main method to run the benchmark from the command line.
   * <p>
   * This method configures and executes the JMH benchmark suite. It includes options for:
   * <ul>
   *   <li>Enabling Java 21 preview features (required for String Templates)</li>
   *   <li>Configuring the benchmark to include all test methods in this class</li>
   *   <li>Setting up appropriate JVM arguments for accurate benchmarking</li>
   * </ul>
   * <p>
   * To run this benchmark, use: java --enable-preview -jar benchmarks.jar StringTemplatePerformanceTest
   *
   * @param args Command line arguments (not used)
   * @throws RunnerException If an error occurs during benchmark execution
   */
  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(StringTemplatePerformanceTest.class.getSimpleName())
        .jvmArgsAppend("--enable-preview") // Enable preview features for Java 21 String Templates
        .forks(1)
        .warmupIterations(5)
        .measurementIterations(5)
        .build();
    new Runner(opt).run();
  }
}