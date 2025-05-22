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
package org.sonatype.nexus.upgrade.datastore.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link SimpleDependencyResolver} with Java 21 Virtual Threads to ensure
 * it works correctly in a concurrent environment.
 * 
 * @since 3.60
 */
public class SimpleDependencyResolverVirtualThreadTest
    extends SimpleDependencyResolverTest
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 10;

  /**
   * Tests that the dependency resolver correctly orders upgrades when executed
   * concurrently by multiple Virtual Threads.
   */
  @Test
  public void testConcurrentOrderUpgrades() throws Exception {
    // Create a thread factory for Virtual Threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses Virtual Threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Reference to store any exception that occurs during concurrent execution
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      
      // Counter to track completed tasks
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Latch to coordinate thread execution
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Expected result from dependency resolution
      List<String> expectedOrder = Arrays.asList("TestMigrationStep", "One", "Z_001_Two", "Z_002_Three");
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Intentionally request ordering with the steps in the wrong order
            Collection<String> result = orderUpgrades(three, one, two, versioned);
            
            // Verify the result is correct
            if (result.size() == 4 && 
                result.containsAll(expectedOrder) && 
                result.toArray()[0].equals(expectedOrder.get(0)) &&
                result.toArray()[1].equals(expectedOrder.get(1)) &&
                result.toArray()[2].equals(expectedOrder.get(2)) &&
                result.toArray()[3].equals(expectedOrder.get(3))) {
              successCount.incrementAndGet();
            }
          } 
          catch (Throwable t) {
            errorRef.compareAndSet(null, t);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue(completed, "Not all tasks completed within the timeout period");
      
      // If any error occurred, rethrow it
      if (errorRef.get() != null) {
        throw new AssertionError("Error during concurrent execution", errorRef.get());
      }
      
      // Verify all tasks succeeded
      assertThat(successCount.get(), is(CONCURRENT_THREADS));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the dependency resolver correctly detects missing dependencies
   * when executed concurrently by multiple Virtual Threads.
   */
  @Test
  public void testConcurrentMissingDependency() throws Exception {
    // Create a thread factory for Virtual Threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses Virtual Threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Counter to track expected exceptions
      AtomicInteger exceptionCount = new AtomicInteger(0);
      
      // Latch to coordinate thread execution
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Request an ordering where a dependency (one) is missing
            orderUpgrades(three, two, versioned);
          } 
          catch (IllegalStateException e) {
            // This exception is expected, count it
            exceptionCount.incrementAndGet();
          } 
          catch (Throwable t) {
            // Unexpected exception type
            t.printStackTrace();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue(completed, "Not all tasks completed within the timeout period");
      
      // Verify all tasks threw the expected exception
      assertThat(exceptionCount.get(), is(CONCURRENT_THREADS));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests the performance of the dependency resolver under high concurrency with Virtual Threads.
   * This test creates a large number of Virtual Threads to validate scalability.
   */
  @Test
  public void testHighConcurrencyPerformance() throws Exception {
    // Use a higher thread count for stress testing
    final int highConcurrencyThreads = 1000;
    
    // Create a thread factory for Virtual Threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses Virtual Threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Reference to store any exception that occurs during concurrent execution
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      
      // Counter to track completed tasks
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Latch to coordinate thread execution
      CountDownLatch latch = new CountDownLatch(highConcurrencyThreads);
      
      // Expected result from dependency resolution
      List<String> expectedOrder = Arrays.asList("TestMigrationStep", "One", "Z_001_Two", "Z_002_Three");
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < highConcurrencyThreads; i++) {
        executor.submit(() -> {
          try {
            // Intentionally request ordering with the steps in the wrong order
            Collection<String> result = orderUpgrades(three, one, two, versioned);
            
            // Verify the result is correct
            if (result.size() == 4 && 
                result.containsAll(expectedOrder) && 
                result.toArray()[0].equals(expectedOrder.get(0)) &&
                result.toArray()[1].equals(expectedOrder.get(1)) &&
                result.toArray()[2].equals(expectedOrder.get(2)) &&
                result.toArray()[3].equals(expectedOrder.get(3))) {
              successCount.incrementAndGet();
            }
          } 
          catch (Throwable t) {
            errorRef.compareAndSet(null, t);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue(completed, "Not all tasks completed within the timeout period");
      
      // If any error occurred, rethrow it
      if (errorRef.get() != null) {
        throw new AssertionError("Error during concurrent execution", errorRef.get());
      }
      
      // Verify all tasks succeeded
      assertThat(successCount.get(), is(highConcurrencyThreads));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to order upgrades using the SimpleDependencyResolver.
   * This method is used by the test methods to perform dependency resolution.
   */
  private Collection<String> orderUpgrades(final DatabaseMigrationStep... steps) {
    return new SimpleDependencyResolver(Arrays.asList(steps)).resolve().stream()
        .map(NexusJavaMigration::getDescription)
        .collect(Collectors.toList());
  }
}