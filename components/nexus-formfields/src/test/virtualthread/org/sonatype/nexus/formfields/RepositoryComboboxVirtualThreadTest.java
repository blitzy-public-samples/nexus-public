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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test {@link RepositoryCombobox} behavior under Virtual Thread execution for Java 21 compatibility.
 * 
 * This test validates that repository combobox components function correctly in highly concurrent situations
 * with Java 21's lightweight threading model, ensuring thread safety and consistent filter behavior under load.
 */
public class RepositoryComboboxVirtualThreadTest
    extends TestSupport
{
  private RepositoryCombobox underTest;
  private ExecutorService executor;
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 10;
  
  @BeforeEach
  void setUp() {
    underTest = new RepositoryCombobox("test");
    // Create a virtual thread per task executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }
  
  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Test that format filters remain consistent when accessed concurrently by many virtual threads.
   * This validates thread safety of the format filter operations in RepositoryCombobox.
   */
  @Test
  @DisplayName("Format filters remain consistent under concurrent virtual thread access")
  void formatFiltersRemainConsistentUnderConcurrentAccess() throws Exception {
    // Set initial format filters
    underTest.includingAnyOfFormats("maven", "docker");
    underTest.excludingAnyOfFormats("nuget", "npm");
    
    // Verify initial state
    assertThat(underTest.getStoreFilters().get("format"), is("maven,docker,!nuget,!npm"));
    
    // Create a latch to synchronize thread completion
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Track any errors that occur during concurrent execution
    AtomicReference<Throwable> error = new AtomicReference<>();
    
    // Track memory usage before the test
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Submit tasks to add and remove format filters concurrently
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Alternate between including and excluding formats
          if (index % 2 == 0) {
            underTest.includingAnyOfFormats("format" + index);
          }
          else {
            underTest.excludingAnyOfFormats("format" + index);
          }
          
          // Verify we can still get store filters without errors
          Map<String, String> filters = underTest.getStoreFilters();
          assertThat(filters, notNullValue());
          assertThat(filters.containsKey("format"), is(true));
        }
        catch (Throwable t) {
          error.compareAndSet(null, t);
        }
        finally {
          latch.countDown();
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads did not complete in time", completed, is(true));
    
    // Check if any errors occurred
    if (error.get() != null) {
      throw new AssertionError("Error during concurrent execution", error.get());
    }
    
    // Verify final state contains expected format filters
    Map<String, String> finalFilters = underTest.getStoreFilters();
    assertThat(finalFilters, notNullValue());
    assertThat(finalFilters.containsKey("format"), is(true));
    
    // Original formats should still be present
    String formatFilter = finalFilters.get("format");
    assertThat(formatFilter.contains("maven"), is(true));
    assertThat(formatFilter.contains("docker"), is(true));
    assertThat(formatFilter.contains("!nuget"), is(true));
    assertThat(formatFilter.contains("!npm"), is(true));
    
    // Track memory usage after the test
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    log.info("Memory used for {} virtual threads: {} bytes", THREAD_COUNT, memoryAfter - memoryBefore);
  }

  /**
   * Test that version policy filters remain consistent when accessed concurrently by many virtual threads.
   * This validates thread safety of the version policy filter operations in RepositoryCombobox.
   */
  @Test
  @DisplayName("Version policy filters remain consistent under concurrent virtual thread access")
  void versionPolicyFiltersRemainConsistentUnderConcurrentAccess() throws Exception {
    // Set initial version policy filters
    underTest.includingAnyOfVersionPolicies("MIXED", "SNAPSHOT");
    underTest.excludingAnyOfVersionPolicies("RELEASE");
    
    // Verify initial state
    assertThat(underTest.getStoreFilters().get("versionPolicies"), is("MIXED,SNAPSHOT,!RELEASE"));
    
    // Create a latch to synchronize thread completion
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Track any errors that occur during concurrent execution
    AtomicReference<Throwable> error = new AtomicReference<>();
    
    // Track memory usage before the test
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Submit tasks to add and remove version policy filters concurrently
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Alternate between including and excluding version policies
          if (index % 2 == 0) {
            underTest.includingAnyOfVersionPolicies("POLICY" + index);
          }
          else {
            underTest.excludingAnyOfVersionPolicies("POLICY" + index);
          }
          
          // Verify we can still get store filters without errors
          Map<String, String> filters = underTest.getStoreFilters();
          assertThat(filters, notNullValue());
          assertThat(filters.containsKey("versionPolicies"), is(true));
        }
        catch (Throwable t) {
          error.compareAndSet(null, t);
        }
        finally {
          latch.countDown();
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads did not complete in time", completed, is(true));
    
    // Check if any errors occurred
    if (error.get() != null) {
      throw new AssertionError("Error during concurrent execution", error.get());
    }
    
    // Verify final state contains expected version policy filters
    Map<String, String> finalFilters = underTest.getStoreFilters();
    assertThat(finalFilters, notNullValue());
    assertThat(finalFilters.containsKey("versionPolicies"), is(true));
    
    // Original version policies should still be present
    String versionPolicyFilter = finalFilters.get("versionPolicies");
    assertThat(versionPolicyFilter.contains("MIXED"), is(true));
    assertThat(versionPolicyFilter.contains("SNAPSHOT"), is(true));
    assertThat(versionPolicyFilter.contains("!RELEASE"), is(true));
    
    // Track memory usage after the test
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    log.info("Memory used for {} virtual threads: {} bytes", THREAD_COUNT, memoryAfter - memoryBefore);
  }

  /**
   * Test that concurrent operations on both format and version policy filters work correctly.
   * This validates that different filter types don't interfere with each other under concurrent access.
   */
  @Test
  @DisplayName("Concurrent operations on multiple filter types work correctly")
  void concurrentOperationsOnMultipleFilterTypesWorkCorrectly() throws Exception {
    // Set initial filters
    underTest.includingAnyOfFormats("maven");
    underTest.includingAnyOfVersionPolicies("SNAPSHOT");
    
    // Create a latch to synchronize thread completion
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT * 2); // Double the threads for both filter types
    
    // Track any errors that occur during concurrent execution
    AtomicReference<Throwable> error = new AtomicReference<>();
    
    // Track operation counts to verify all operations were performed
    AtomicInteger formatOperations = new AtomicInteger(0);
    AtomicInteger versionPolicyOperations = new AtomicInteger(0);
    
    // Track memory usage before the test
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Submit tasks to modify format filters concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      CompletableFuture.runAsync(() -> {
        try {
          // Alternate between including and excluding formats
          if (index % 2 == 0) {
            underTest.includingAnyOfFormats("format" + index);
          }
          else {
            underTest.excludingAnyOfFormats("format" + index);
          }
          formatOperations.incrementAndGet();
        }
        catch (Throwable t) {
          error.compareAndSet(null, t);
        }
        finally {
          latch.countDown();
        }
      }, executor);
    }
    
    // Submit tasks to modify version policy filters concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      CompletableFuture.runAsync(() -> {
        try {
          // Alternate between including and excluding version policies
          if (index % 2 == 0) {
            underTest.includingAnyOfVersionPolicies("POLICY" + index);
          }
          else {
            underTest.excludingAnyOfVersionPolicies("POLICY" + index);
          }
          versionPolicyOperations.incrementAndGet();
        }
        catch (Throwable t) {
          error.compareAndSet(null, t);
        }
        finally {
          latch.countDown();
        }
      }, executor);
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads did not complete in time", completed, is(true));
    
    // Check if any errors occurred
    if (error.get() != null) {
      throw new AssertionError("Error during concurrent execution", error.get());
    }
    
    // Verify all operations were performed
    assertThat(formatOperations.get(), is(THREAD_COUNT));
    assertThat(versionPolicyOperations.get(), is(THREAD_COUNT));
    
    // Verify final state contains both filter types
    Map<String, String> finalFilters = underTest.getStoreFilters();
    assertThat(finalFilters, notNullValue());
    assertThat(finalFilters.containsKey("format"), is(true));
    assertThat(finalFilters.containsKey("versionPolicies"), is(true));
    
    // Original values should still be present
    String formatFilter = finalFilters.get("format");
    String versionPolicyFilter = finalFilters.get("versionPolicies");
    assertThat(formatFilter.contains("maven"), is(true));
    assertThat(versionPolicyFilter.contains("SNAPSHOT"), is(true));
    
    // Track memory usage after the test
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    log.info("Memory used for {} virtual threads: {} bytes", THREAD_COUNT * 2, memoryAfter - memoryBefore);
  }

  /**
   * Test that the includeAnEntryForAllRepositories method is thread-safe under concurrent access.
   */
  @Test
  @DisplayName("includeAnEntryForAllRepositories method is thread-safe")
  void includeAnEntryForAllRepositoriesIsThreadSafe() throws Exception {
    // Create a latch to synchronize thread completion
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Track any errors that occur during concurrent execution
    AtomicReference<Throwable> error = new AtomicReference<>();
    
    // Submit tasks to call includeAnEntryForAllRepositories concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          underTest.includeAnEntryForAllRepositories();
          
          // Verify the method had the expected effect
          assertThat(underTest.getStoreFilters(), nullValue());
          assertThat(underTest.getStoreApi(), is("coreui_Repository.readReferencesAddingEntryForAll"));
        }
        catch (Throwable t) {
          error.compareAndSet(null, t);
        }
        finally {
          latch.countDown();
        }
      }, executor);
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads did not complete in time", completed, is(true));
    
    // Check if any errors occurred
    if (error.get() != null) {
      throw new AssertionError("Error during concurrent execution", error.get());
    }
    
    // Verify final state
    assertThat(underTest.getStoreFilters(), nullValue());
    assertThat(underTest.getStoreApi(), is("coreui_Repository.readReferencesAddingEntryForAll"));
  }
}