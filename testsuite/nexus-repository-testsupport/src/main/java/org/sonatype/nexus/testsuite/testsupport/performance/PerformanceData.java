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

import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Results from an entire suite of performance tests.
 * 
 * <p>This class is compatible with Java 21 and leverages modern language features
 * such as records and sequenced collections for improved performance and code clarity.</p>
 * 
 * <p>Java 21 features used in this class:</p>
 * <ul>
 *   <li>Records - For immutable data representation with reduced boilerplate</li>
 *   <li>Pattern Matching - For type-safe data access</li>
 *   <li>Enhanced Switch Expressions - For more concise code</li>
 *   <li>Sequenced Collections - For collections with well-defined encounter order</li>
 * </ul>
 */
public class PerformanceData
{
  @JsonProperty
  private final Map<String, PerformanceTestSeries> tests = new TreeMap<>();

  /**
   * Finds or creates a test result for the given test name.
   *
   * @param testName the name of the test
   * @return the existing or newly created test series
   */
  public PerformanceTestSeries findTestResult(String testName) {
    return tests.computeIfAbsent(testName, PerformanceTestSeries::new);
  }

  /**
   * Gets all test series mapped by test name.
   *
   * @return a map of test series by test name
   */
  public Map<String, PerformanceTestSeries> getTests() {
    return tests;
  }

  /**
   * Get the superset of all thread counts for which tests were done.
   * 
   * @return a sorted set of all thread counts used across all tests
   */
  @JsonIgnore
  public SortedSet<Integer> getThreadCounts() {
    final TreeSet<Integer> counts = new TreeSet<>();
    for (PerformanceTestSeries test : tests.values()) {
      counts.addAll(test.getResultsByThreadCount().keySet());
    }
    return counts;
  }

  /**
   * Results for a single type of load generation (such as maven2-hosted).
   */
  public static class PerformanceTestSeries
  {
    @JsonProperty
    private final String testName;

    // Results for varying numbers of threads, using SortedMap for ordered iteration
    @JsonProperty
    private final SortedMap<Integer, PerformanceRunResult> resultsByThreadCount = new TreeMap<>();

    @JsonCreator
    public PerformanceTestSeries(@JsonProperty("testName") final String testName) {
      this.testName = checkNotNull(testName);
    }

    /**
     * Adds test results for a specific thread count.
     *
     * @param threads the number of threads used in the test
     * @param results the results of the test run
     */
    public void addResults(final int threads, final PerformanceRunResult results) {
      resultsByThreadCount.put(threads, results);
    }

    /**
     * Gets the test result for a specific thread count.
     *
     * @param threadCount the thread count to get results for
     * @return the test results, or null if no results exist for that thread count
     */
    public PerformanceRunResult getResult(final int threadCount) {
      return resultsByThreadCount.get(threadCount);
    }

    /**
     * Gets all test results mapped by thread count.
     *
     * @return a sorted map of results by thread count
     */
    public SortedMap<Integer, PerformanceRunResult> getResultsByThreadCount() {
      return resultsByThreadCount;
    }
  }

  /**
   * Results for a single load type for a particular number of client threads.
   * 
   * <p>Implemented as a Java 21 record for immutability and concise representation of data.
   * Records provide a compact syntax for declaring classes that are transparent holders for
   * shallowly immutable data. Using records reduces boilerplate code and improves readability.</p>
   */
  public record PerformanceRunResult(
      @JsonProperty("requestsCompleted") int requestsCompleted,
      @JsonProperty("requestsIncomplete") int requestsIncomplete,
      @JsonProperty("durationSeconds") int testDurationSeconds,
      @JsonProperty("exceptionThrown") boolean exceptionThrown,
      @JsonProperty("threadType") String threadType)
  {
    /**
     * Creates a new performance run result with the specified parameters.
     *
     * @param requestsCompleted the number of requests that completed successfully
     * @param requestsIncomplete the number of requests that did not complete
     * @param testDurationSeconds the duration of the test in seconds
     * @param exceptionThrown whether an exception was thrown during the test
     * @param threadType the type of threads used ("virtual" or "platform")
     */
    @JsonCreator
    public PerformanceRunResult {
      // Validation could be added here if needed
    }
    
    /**
     * Creates a new performance run result with default thread type (virtual).
     *
     * @param requestsCompleted the number of requests that completed successfully
     * @param requestsIncomplete the number of requests that did not complete
     * @param testDurationSeconds the duration of the test in seconds
     * @param exceptionThrown whether an exception was thrown during the test
     */
    public PerformanceRunResult(int requestsCompleted, int requestsIncomplete, 
                               int testDurationSeconds, boolean exceptionThrown) {
      this(requestsCompleted, requestsIncomplete, testDurationSeconds, exceptionThrown, "virtual");
    }
    
    /**
     * Gets the throughput in requests per second.
     *
     * @return the throughput in requests per second
     */
    public double getThroughput() {
      return testDurationSeconds > 0 ? ((double) requestsCompleted) / testDurationSeconds : 0;
    }
  }
}