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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceRunResult;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceTestSeries;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Tag;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests {@link PerformanceDataIO} with Java 21 compatibility.
 * <p>
 * This test validates the serialization and deserialization of performance data,
 * including under virtual thread execution to ensure compatibility with Java 21's
 * concurrency enhancements.
 */
@DisplayName("Performance Data I/O Tests")
public class PerformanceDataIOTest
    extends TestSupport
{
  private File jsonData;

  @BeforeEach
  public void setJsonFileLocation(TestInfo testInfo) throws IOException {
    jsonData = File.createTempFile(testInfo.getDisplayName().replace(':', '_'), "json");
    jsonData.delete(); // We just need a location, not an empty file to confuse the loader.
  }

  /**
   * Tests basic serialization and deserialization of performance data.
   */
  @Test
  @DisplayName("Basic round-trip serialization test")
  void roundTrip() throws Exception {
    final PerformanceData perfData = PerformanceDataIO.loadTestData(jsonData);

    final PerformanceTestSeries roundTrip = perfData.findTestResult("roundTrip");
    roundTrip.addResults(1, new PerformanceRunResult(1, 2, 60, true));

    PerformanceDataIO.saveTestData(perfData, jsonData);

    final PerformanceData loaded = PerformanceDataIO.loadTestData(jsonData);

    final PerformanceTestSeries testResult = loaded.findTestResult("roundTrip");
    final PerformanceRunResult result = testResult.getResult(1);

    assertThat(result.getRequestsCompleted(), is(1));
    assertThat(result.getRequestsIncomplete(), is(2));
    assertThat(result.getTestDurationSeconds(), is(60));
    assertThat(result.isExceptionThrown(), is(true));
  }

  /**
   * Tests serialization and deserialization of performance data with complex nested structures.
   */
  @Test
  @DisplayName("Complex data structure serialization test")
  void complexDataStructures() throws Exception {
    final PerformanceData perfData = PerformanceDataIO.loadTestData(jsonData);

    // Add multiple test series with different results
    final PerformanceTestSeries series1 = perfData.findTestResult("series1");
    series1.addResults(1, new PerformanceRunResult(100, 5, 30, false));
    series1.addResults(2, new PerformanceRunResult(200, 10, 45, false));

    final PerformanceTestSeries series2 = perfData.findTestResult("series2");
    series2.addResults(1, new PerformanceRunResult(50, 2, 15, true));

    PerformanceDataIO.saveTestData(perfData, jsonData);

    final PerformanceData loaded = PerformanceDataIO.loadTestData(jsonData);

    // Verify first series
    final PerformanceTestSeries loadedSeries1 = loaded.findTestResult("series1");
    final PerformanceRunResult result1 = loadedSeries1.getResult(1);
    final PerformanceRunResult result2 = loadedSeries1.getResult(2);

    assertThat(result1.getRequestsCompleted(), is(100));
    assertThat(result1.getRequestsIncomplete(), is(5));
    assertThat(result1.getTestDurationSeconds(), is(30));
    assertThat(result1.isExceptionThrown(), is(false));

    assertThat(result2.getRequestsCompleted(), is(200));
    assertThat(result2.getRequestsIncomplete(), is(10));
    assertThat(result2.getTestDurationSeconds(), is(45));
    assertThat(result2.isExceptionThrown(), is(false));

    // Verify second series
    final PerformanceTestSeries loadedSeries2 = loaded.findTestResult("series2");
    final PerformanceRunResult result3 = loadedSeries2.getResult(1);

    assertThat(result3.getRequestsCompleted(), is(50));
    assertThat(result3.getRequestsIncomplete(), is(2));
    assertThat(result3.getTestDurationSeconds(), is(15));
    assertThat(result3.isExceptionThrown(), is(true));
  }

  /**
   * Tests serialization and deserialization of performance data using virtual threads.
   * <p>
   * This test validates that the PerformanceDataIO operations work correctly when executed
   * on Java 21's virtual threads, ensuring compatibility with the new concurrency model.
   */
  @Test
  @DisplayName("Virtual thread serialization test")
  @Tag("java21")
  void virtualThreadSerialization() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Number of concurrent operations to perform
      int operationCount = 100;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicReference<Exception> error = new AtomicReference<>();
      
      // Prepare test data
      final PerformanceData perfData = PerformanceDataIO.loadTestData(jsonData);
      final PerformanceTestSeries virtualSeries = perfData.findTestResult("virtualThreadTest");
      virtualSeries.addResults(1, new PerformanceRunResult(500, 10, 120, false));
      
      // Save the data once to ensure the file exists
      PerformanceDataIO.saveTestData(perfData, jsonData);
      
      // Perform concurrent load/save operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int iteration = i;
        executor.submit(() -> {
          try {
            // Load data
            PerformanceData threadData = PerformanceDataIO.loadTestData(jsonData);
            
            // Modify data with thread-specific values
            PerformanceTestSeries series = threadData.findTestResult("virtualThreadTest-" + iteration);
            series.addResults(1, new PerformanceRunResult(iteration, iteration * 2, 30 + iteration, iteration % 2 == 0));
            
            // Save data
            PerformanceDataIO.saveTestData(threadData, jsonData);
          } 
          catch (Exception e) {
            error.compareAndSet(null, e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Check for errors
      Exception exception = error.get();
      if (exception != null) {
        throw new AssertionError("Error during virtual thread execution", exception);
      }
      
      assertThat("All virtual thread operations should complete", completed, is(true));
      
      // Verify the data can still be loaded correctly
      final PerformanceData loadedData = PerformanceDataIO.loadTestData(jsonData);
      assertThat(loadedData, notNullValue());
      
      // Verify the original data is still intact
      final PerformanceTestSeries originalSeries = loadedData.findTestResult("virtualThreadTest");
      final PerformanceRunResult originalResult = originalSeries.getResult(1);
      
      assertThat(originalResult.getRequestsCompleted(), is(500));
      assertThat(originalResult.getRequestsIncomplete(), is(10));
      assertThat(originalResult.getTestDurationSeconds(), is(120));
      assertThat(originalResult.isExceptionThrown(), is(false));
      
      // Verify at least one of the thread-specific entries exists
      // (We don't check all because the last write wins, and order is non-deterministic)
      boolean foundThreadSpecificEntry = false;
      for (int i = 0; i < operationCount; i++) {
        PerformanceTestSeries threadSeries = loadedData.findTestResult("virtualThreadTest-" + i);
        if (threadSeries != null && threadSeries.getResult(1) != null) {
          foundThreadSpecificEntry = true;
          break;
        }
      }
      
      assertThat("At least one thread-specific entry should exist", foundThreadSpecificEntry, is(true));
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests concurrent serialization and deserialization with a mix of platform and virtual threads.
   * <p>
   * This test validates that the PerformanceDataIO operations work correctly in a mixed
   * threading environment, ensuring compatibility across thread types in Java 21.
   */
  @Test
  @DisplayName("Mixed thread type serialization test")
  @Tag("java21")
  void mixedThreadTypeSerialization() throws Exception {
    // Create both thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    ExecutorService platformExecutor = Executors.newThreadPerTaskExecutor(platformThreadFactory);
    
    try {
      // Number of operations per thread type
      int operationsPerType = 50;
      CountDownLatch latch = new CountDownLatch(operationsPerType * 2); // Total operations
      AtomicReference<Exception> error = new AtomicReference<>();
      
      // Prepare test data
      final PerformanceData perfData = PerformanceDataIO.loadTestData(jsonData);
      final PerformanceTestSeries mixedSeries = perfData.findTestResult("mixedThreadTest");
      mixedSeries.addResults(1, new PerformanceRunResult(1000, 20, 180, false));
      
      // Save the data once to ensure the file exists
      PerformanceDataIO.saveTestData(perfData, jsonData);
      
      // Submit tasks to both executor types
      for (int i = 0; i < operationsPerType; i++) {
        final int iteration = i;
        
        // Virtual thread task
        virtualExecutor.submit(() -> {
          try {
            PerformanceData threadData = PerformanceDataIO.loadTestData(jsonData);
            PerformanceTestSeries series = threadData.findTestResult("virtualThread-" + iteration);
            series.addResults(1, new PerformanceRunResult(iteration, iteration, 10, false));
            PerformanceDataIO.saveTestData(threadData, jsonData);
          } 
          catch (Exception e) {
            error.compareAndSet(null, e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Platform thread task
        platformExecutor.submit(() -> {
          try {
            PerformanceData threadData = PerformanceDataIO.loadTestData(jsonData);
            PerformanceTestSeries series = threadData.findTestResult("platformThread-" + iteration);
            series.addResults(1, new PerformanceRunResult(iteration * 10, iteration * 10, 20, true));
            PerformanceDataIO.saveTestData(threadData, jsonData);
          } 
          catch (Exception e) {
            error.compareAndSet(null, e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Check for errors
      Exception exception = error.get();
      if (exception != null) {
        throw new AssertionError("Error during mixed thread execution", exception);
      }
      
      assertThat("All mixed thread operations should complete", completed, is(true));
      
      // Verify the data can still be loaded correctly
      final PerformanceData loadedData = PerformanceDataIO.loadTestData(jsonData);
      assertThat(loadedData, notNullValue());
      
      // Verify the original data is still intact
      final PerformanceTestSeries originalSeries = loadedData.findTestResult("mixedThreadTest");
      final PerformanceRunResult originalResult = originalSeries.getResult(1);
      
      assertThat(originalResult.getRequestsCompleted(), is(1000));
      assertThat(originalResult.getRequestsIncomplete(), is(20));
      assertThat(originalResult.getTestDurationSeconds(), is(180));
      assertThat(originalResult.isExceptionThrown(), is(false));
    } 
    finally {
      virtualExecutor.shutdown();
      platformExecutor.shutdown();
      virtualExecutor.awaitTermination(5, TimeUnit.SECONDS);
      platformExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}