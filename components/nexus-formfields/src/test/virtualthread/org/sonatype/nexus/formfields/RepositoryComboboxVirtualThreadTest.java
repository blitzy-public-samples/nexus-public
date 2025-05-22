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
package org.sonatype.nexus.formfields;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests {@link RepositoryCombobox} behavior under Virtual Thread execution.
 * 
 * This test class validates that RepositoryCombobox operations remain thread-safe
 * and consistent when accessed concurrently by many Virtual Threads, which is
 * important for ensuring compatibility with Java 21's lightweight threading model.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
public class RepositoryComboboxVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int THREAD_COUNT = 5000;
  private static final int WARMUP_COUNT = 100;
  private static final String[] FORMATS = {"maven", "npm", "docker", "raw", "nuget", "pypi", "rubygems", "yum"};
  private static final String[] VERSION_POLICIES = {"RELEASE", "SNAPSHOT", "MIXED"};
  
  private RepositoryCombobox underTest;
  private ExecutorService executor;
  private MemoryMXBean memoryMXBean;
  private long initialMemoryUsage;
  
  @BeforeEach
  public void setUp() {
    underTest = new RepositoryCombobox("test");
    executor = Executors.newVirtualThreadPerTaskExecutor();
    memoryMXBean = ManagementFactory.getMemoryMXBean();
    
    // Warm up the JVM to stabilize memory measurements
    warmUp();
    
    // Record initial memory usage after warm-up
    System.gc();
    initialMemoryUsage = memoryMXBean.getHeapMemoryUsage().getUsed();
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    executor.shutdown();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
  }
  
  /**
   * Warm up the JVM to stabilize memory measurements.
   */
  private void warmUp() {
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < WARMUP_COUNT; i++) {
      futures.add(CompletableFuture.runAsync(() -> {
        RepositoryCombobox combobox = new RepositoryCombobox("warmup");
        combobox.includingAnyOfFormats("maven");
        combobox.excludingAnyOfFormats("npm");
        combobox.getStoreFilters();
      }, executor));
    }
    
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
  }
  
  /**
   * Tests that format filters can be safely applied and read concurrently by many Virtual Threads.
   * 
   * This test creates thousands of Virtual Threads that simultaneously modify and read
   * the format filters, then verifies that the results are consistent and thread-safe.
   */
  @Test
  @DisplayName("Format filters should be thread-safe with Virtual Threads")
  public void formatFiltersShouldBeThreadSafeWithVirtualThreads() {
    // Track successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a map to track filter values seen by different threads
    ConcurrentHashMap<String, Integer> observedFilters = new ConcurrentHashMap<>();
    
    // Create thousands of virtual threads that modify and read format filters
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          // Each thread includes and excludes different formats based on its index
          String includeFormat = FORMATS[index % FORMATS.length];
          String excludeFormat = FORMATS[(index + 1) % FORMATS.length];
          
          // Apply filters
          underTest.includingAnyOfFormats(includeFormat);
          underTest.excludingAnyOfFormats(excludeFormat);
          
          // Get and verify filters
          Map<String, String> filters = underTest.getStoreFilters();
          assertThat(filters, notNullValue());
          assertThat(filters.containsKey("format"), is(true));
          
          String formatFilter = filters.get("format");
          assertThat(formatFilter, containsString(includeFormat));
          assertThat(formatFilter, containsString(STR."!\{excludeFormat}"));
          
          // Track observed filter values
          observedFilters.put(formatFilter, observedFilters.getOrDefault(formatFilter, 0) + 1);
          
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          // Log any exceptions
          log.error("Error in virtual thread {}", index, e);
        }
      }, executor));
    }
    
    // Wait for all threads to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify all operations completed successfully
    assertThat(successCount.get(), is(THREAD_COUNT));
    
    // Verify memory usage is reasonable (should be much less than with platform threads)
    System.gc();
    long finalMemoryUsage = memoryMXBean.getHeapMemoryUsage().getUsed();
    long memoryPerThread = (finalMemoryUsage - initialMemoryUsage) / THREAD_COUNT;
    
    log.info("Memory usage per virtual thread: {} bytes", memoryPerThread);
    
    // Virtual threads should use significantly less memory than platform threads
    // A reasonable threshold is 1KB per thread, which is much less than platform threads
    assertThat(memoryPerThread, lessThan(1024L));
    
    // Log observed filter combinations for analysis
    log.info("Observed {} distinct filter combinations", observedFilters.size());
    observedFilters.forEach((filter, count) -> 
        log.debug("Filter '{}' observed {} times", filter, count));
  }
  
  /**
   * Tests that version policy filters can be safely applied and read concurrently by many Virtual Threads.
   * 
   * This test creates thousands of Virtual Threads that simultaneously modify and read
   * the version policy filters, then verifies that the results are consistent and thread-safe.
   */
  @Test
  @DisplayName("Version policy filters should be thread-safe with Virtual Threads")
  public void versionPolicyFiltersShouldBeThreadSafeWithVirtualThreads() {
    // Track successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a map to track filter values seen by different threads
    ConcurrentHashMap<String, Integer> observedFilters = new ConcurrentHashMap<>();
    
    // Create thousands of virtual threads that modify and read version policy filters
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          // Each thread includes and excludes different version policies based on its index
          String includePolicy = VERSION_POLICIES[index % VERSION_POLICIES.length];
          String excludePolicy = VERSION_POLICIES[(index + 1) % VERSION_POLICIES.length];
          
          // Apply filters
          underTest.includingAnyOfVersionPolicies(includePolicy);
          underTest.excludingAnyOfVersionPolicies(excludePolicy);
          
          // Get and verify filters
          Map<String, String> filters = underTest.getStoreFilters();
          assertThat(filters, notNullValue());
          assertThat(filters.containsKey("versionPolicies"), is(true));
          
          String policyFilter = filters.get("versionPolicies");
          assertThat(policyFilter, containsString(includePolicy));
          assertThat(policyFilter, containsString(STR."!\{excludePolicy}"));
          
          // Track observed filter values
          observedFilters.put(policyFilter, observedFilters.getOrDefault(policyFilter, 0) + 1);
          
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          // Log any exceptions
          log.error("Error in virtual thread {}", index, e);
        }
      }, executor));
    }
    
    // Wait for all threads to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify all operations completed successfully
    assertThat(successCount.get(), is(THREAD_COUNT));
    
    // Verify memory usage is reasonable
    System.gc();
    long finalMemoryUsage = memoryMXBean.getHeapMemoryUsage().getUsed();
    long memoryPerThread = (finalMemoryUsage - initialMemoryUsage) / THREAD_COUNT;
    
    log.info("Memory usage per virtual thread: {} bytes", memoryPerThread);
    
    // Virtual threads should use significantly less memory than platform threads
    assertThat(memoryPerThread, lessThan(1024L));
    
    // Log observed filter combinations for analysis
    log.info("Observed {} distinct filter combinations", observedFilters.size());
    observedFilters.forEach((filter, count) -> 
        log.debug("Filter '{}' observed {} times", filter, count));
  }
  
  /**
   * Tests that combined format and version policy filters can be safely applied and read
   * concurrently by many Virtual Threads.
   * 
   * This test creates thousands of Virtual Threads that simultaneously modify and read
   * both format and version policy filters, then verifies that the results are consistent and thread-safe.
   */
  @Test
  @DisplayName("Combined filters should be thread-safe with Virtual Threads")
  public void combinedFiltersShouldBeThreadSafeWithVirtualThreads() {
    // Track successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create thousands of virtual threads that modify and read both types of filters
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          // Each thread includes and excludes different formats and policies based on its index
          String includeFormat = FORMATS[index % FORMATS.length];
          String excludeFormat = FORMATS[(index + 1) % FORMATS.length];
          String includePolicy = VERSION_POLICIES[index % VERSION_POLICIES.length];
          String excludePolicy = VERSION_POLICIES[(index + 1) % VERSION_POLICIES.length];
          
          // Apply filters
          underTest.includingAnyOfFormats(includeFormat);
          underTest.excludingAnyOfFormats(excludeFormat);
          underTest.includingAnyOfVersionPolicies(includePolicy);
          underTest.excludingAnyOfVersionPolicies(excludePolicy);
          
          // Get and verify filters
          Map<String, String> filters = underTest.getStoreFilters();
          assertThat(filters, notNullValue());
          assertThat(filters.containsKey("format"), is(true));
          assertThat(filters.containsKey("versionPolicies"), is(true));
          
          String formatFilter = filters.get("format");
          String policyFilter = filters.get("versionPolicies");
          
          assertThat(formatFilter, containsString(includeFormat));
          assertThat(formatFilter, containsString(STR."!\{excludeFormat}"));
          assertThat(policyFilter, containsString(includePolicy));
          assertThat(policyFilter, containsString(STR."!\{excludePolicy}"));
          
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          // Log any exceptions
          log.error("Error in virtual thread {}", index, e);
        }
      }, executor));
    }
    
    // Wait for all threads to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify all operations completed successfully
    assertThat(successCount.get(), is(THREAD_COUNT));
    
    // Verify memory usage is reasonable
    System.gc();
    long finalMemoryUsage = memoryMXBean.getHeapMemoryUsage().getUsed();
    long memoryPerThread = (finalMemoryUsage - initialMemoryUsage) / THREAD_COUNT;
    
    log.info("Memory usage per virtual thread: {} bytes", memoryPerThread);
    
    // Virtual threads should use significantly less memory than platform threads
    assertThat(memoryPerThread, lessThan(1024L));
  }
  
  /**
   * Tests that the async repository data fetching method works correctly with Virtual Threads.
   * 
   * This test verifies that the fetchRepositoryDataAsync method correctly uses Virtual Threads
   * to fetch repository data asynchronously and returns the expected results.
   */
  @Test
  @DisplayName("Async repository data fetching should work with Virtual Threads")
  public void asyncRepositoryDataFetchingShouldWorkWithVirtualThreads() {
    // Create a list of repository IDs to fetch
    List<String> repositoryIds = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      repositoryIds.add("repo-" + i);
    }
    
    // Fetch repository data asynchronously
    CompletableFuture<List<RepositoryCombobox.RepositoryInfo>> future = 
        underTest.fetchRepositoryDataAsync(repositoryIds);
    
    // Wait for the future to complete and get the results
    List<RepositoryCombobox.RepositoryInfo> results = future.join();
    
    // Verify the results
    assertThat(results.size(), is(repositoryIds.size()));
    for (int i = 0; i < repositoryIds.size(); i++) {
      RepositoryCombobox.RepositoryInfo info = results.get(i);
      assertThat(info.id(), is(repositoryIds.get(i)));
      assertThat(info.name(), is("Repository " + repositoryIds.get(i)));
    }
  }
  
  /**
   * Tests that the repository info processing method works correctly with pattern matching.
   * 
   * This test verifies that the processRepositoryInfo method correctly uses pattern matching
   * to process repository information and returns the expected results.
   */
  @Test
  @DisplayName("Repository info processing should work with pattern matching")
  public void repositoryInfoProcessingShouldWorkWithPatternMatching() {
    // Create repository info objects with different ID patterns
    RepositoryCombobox.RepositoryInfo hostedRepo = 
        new RepositoryCombobox.RepositoryInfo("hosted-maven", "Maven Hosted");
    RepositoryCombobox.RepositoryInfo proxyRepo = 
        new RepositoryCombobox.RepositoryInfo("proxy-npm", "NPM Proxy");
    RepositoryCombobox.RepositoryInfo groupRepo = 
        new RepositoryCombobox.RepositoryInfo("group-docker", "Docker Group");
    RepositoryCombobox.RepositoryInfo otherRepo = 
        new RepositoryCombobox.RepositoryInfo("other-repo", "Other Repository");
    
    // Process repository info objects
    String hostedResult = underTest.processRepositoryInfo(hostedRepo);
    String proxyResult = underTest.processRepositoryInfo(proxyRepo);
    String groupResult = underTest.processRepositoryInfo(groupRepo);
    String otherResult = underTest.processRepositoryInfo(otherRepo);
    
    // Verify the results
    assertThat(hostedResult, is("Hosted Repository: Maven Hosted (hosted-maven)"));
    assertThat(proxyResult, is("Proxy Repository: NPM Proxy (proxy-npm)"));
    assertThat(groupResult, is("Group Repository: Docker Group (group-docker)"));
    assertThat(otherResult, is("Repository: Other Repository (other-repo)"));
  }
}