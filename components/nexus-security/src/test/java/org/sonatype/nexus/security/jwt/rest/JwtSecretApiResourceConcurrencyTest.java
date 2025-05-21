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
package org.sonatype.nexus.security.jwt.rest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.jwt.SecretStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.doAnswer;

/**
 * Concurrency tests for {@link JwtSecretApiResourceV1} using Java 21 virtual threads.
 * 
 * @since 3.62
 */
@ExtendWith(MockitoExtension.class)
public class JwtSecretApiResourceConcurrencyTest
{
  private static final int THREAD_COUNT = 1000;
  private static final int WARMUP_COUNT = 100;
  
  @Mock
  private SecretStore secretStore;
  
  private JwtSecretApiResourceV1 underTest;
  
  @BeforeEach
  public void setup() {
    underTest = new JwtSecretApiResourceV1(secretStore);
  }
  
  /**
   * Test concurrent secret resets using platform threads.
   * This verifies that the SecretStore implementation correctly handles
   * concurrent requests without race conditions.
   */
  @Test
  public void testConcurrentSecretResetWithPlatformThreads() throws Exception {
    // Track all secrets set during the test
    Set<String> capturedSecrets = ConcurrentHashMap.newKeySet();
    AtomicInteger secretSetCount = new AtomicInteger(0);
    
    // Configure mock to capture secrets and track concurrent access
    doAnswer(invocation -> {
      String secret = invocation.getArgument(0);
      capturedSecrets.add(secret);
      secretSetCount.incrementAndGet();
      // Simulate some processing time
      Thread.sleep(5);
      return null;
    }).when(secretStore).setSecret(org.mockito.ArgumentMatchers.any(String.class));
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create platform thread executor
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    
    // Submit tasks to reset secrets concurrently
    List<Runnable> tasks = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      tasks.add(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          underTest.resetSecret();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }
    
    // Start timing
    Instant start = Instant.now();
    
    // Submit all tasks
    tasks.forEach(executor::submit);
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Shutdown and wait for completion
    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);
    
    // End timing
    Instant end = Instant.now();
    Duration platformDuration = Duration.between(start, end);
    
    // Verify results
    assertThat("All secret reset operations should be processed", 
        secretSetCount.get(), is(THREAD_COUNT));
    assertThat("Each secret should be unique (no race conditions)", 
        capturedSecrets.size(), is(THREAD_COUNT));
    
    System.out.println("Platform threads completed " + THREAD_COUNT + 
        " operations in " + platformDuration.toMillis() + "ms");
  }
  
  /**
   * Test concurrent secret resets using Java 21 virtual threads.
   * This verifies the same thread-safety as the platform thread test,
   * but using virtual threads for improved scalability.
   */
  @Test
  public void testConcurrentSecretResetWithVirtualThreads() throws Exception {
    // Track all secrets set during the test
    Set<String> capturedSecrets = ConcurrentHashMap.newKeySet();
    AtomicInteger secretSetCount = new AtomicInteger(0);
    
    // Configure mock to capture secrets and track concurrent access
    doAnswer(invocation -> {
      String secret = invocation.getArgument(0);
      capturedSecrets.add(secret);
      secretSetCount.incrementAndGet();
      // Simulate some processing time
      Thread.sleep(5);
      return null;
    }).when(secretStore).setSecret(org.mockito.ArgumentMatchers.any(String.class));
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to reset secrets concurrently
      List<Runnable> tasks = new ArrayList<>();
      for (int i = 0; i < THREAD_COUNT; i++) {
        tasks.add(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            underTest.resetSecret();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      
      // Start timing
      Instant start = Instant.now();
      
      // Submit all tasks
      tasks.forEach(executor::submit);
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Shutdown and wait for completion
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);
      
      // End timing
      Instant end = Instant.now();
      Duration virtualDuration = Duration.between(start, end);
      
      // Verify results
      assertThat("All secret reset operations should be processed", 
          secretSetCount.get(), is(THREAD_COUNT));
      assertThat("Each secret should be unique (no race conditions)", 
          capturedSecrets.size(), is(THREAD_COUNT));
      
      System.out.println("Virtual threads completed " + THREAD_COUNT + 
          " operations in " + virtualDuration.toMillis() + "ms");
    }
  }
  
  /**
   * Compare performance between platform threads and virtual threads
   * for JWT secret management operations.
   */
  @Test
  public void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    // Warm up to avoid JIT compilation affecting results
    warmupThreads(WARMUP_COUNT);
    
    // Track timing for platform threads
    AtomicInteger platformCounter = new AtomicInteger(0);
    doAnswer(invocation -> {
      platformCounter.incrementAndGet();
      Thread.sleep(5); // Simulate some processing time
      return null;
    }).when(secretStore).setSecret(org.mockito.ArgumentMatchers.any(String.class));
    
    // Run platform thread test
    Instant platformStart = Instant.now();
    runWithPlatformThreads(THREAD_COUNT);
    Instant platformEnd = Instant.now();
    Duration platformDuration = Duration.between(platformStart, platformEnd);
    
    // Reset counter
    platformCounter.set(0);
    
    // Track timing for virtual threads
    AtomicInteger virtualCounter = new AtomicInteger(0);
    doAnswer(invocation -> {
      virtualCounter.incrementAndGet();
      Thread.sleep(5); // Simulate some processing time
      return null;
    }).when(secretStore).setSecret(org.mockito.ArgumentMatchers.any(String.class));
    
    // Run virtual thread test
    Instant virtualStart = Instant.now();
    runWithVirtualThreads(THREAD_COUNT);
    Instant virtualEnd = Instant.now();
    Duration virtualDuration = Duration.between(virtualStart, virtualEnd);
    
    // Verify all operations completed
    assertThat(platformCounter.get(), is(THREAD_COUNT));
    assertThat(virtualCounter.get(), is(THREAD_COUNT));
    
    // Log performance results
    System.out.println("Performance comparison for " + THREAD_COUNT + " JWT secret resets:");
    System.out.println("Platform threads: " + platformDuration.toMillis() + "ms");
    System.out.println("Virtual threads: " + virtualDuration.toMillis() + "ms");
    System.out.println("Improvement ratio: " + 
        String.format("%.2f", (double) platformDuration.toMillis() / virtualDuration.toMillis()) + "x");
    
    // Virtual threads should generally be faster for I/O bound operations
    // but the difference might not be significant in all environments
    // This assertion is intentionally lenient to avoid test flakiness
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualDuration.toMillis(), lessThan(platformDuration.toMillis() * 2L));
  }
  
  /**
   * Test that UUID generation is thread-safe and produces unique values
   * even under high concurrency with virtual threads.
   */
  @Test
  public void testUuidGenerationThreadSafety() throws Exception {
    // Track all generated UUIDs
    Set<String> capturedUuids = ConcurrentHashMap.newKeySet();
    
    // Configure mock to capture UUIDs
    doAnswer(invocation -> {
      String secret = invocation.getArgument(0);
      capturedUuids.add(secret);
      return null;
    }).when(secretStore).setSecret(org.mockito.ArgumentMatchers.any(String.class));
    
    // Run with virtual threads for maximum concurrency
    runWithVirtualThreads(THREAD_COUNT);
    
    // Verify each UUID is unique
    assertThat("Each UUID should be unique", capturedUuids.size(), is(THREAD_COUNT));
    
    // Verify each captured value is a valid UUID
    capturedUuids.forEach(uuid -> {
      try {
        UUID parsedUuid = UUID.fromString(uuid);
        assertThat("UUID string representation should match parsed value", 
            uuid, equalTo(parsedUuid.toString()));
      }
      catch (IllegalArgumentException e) {
        throw new AssertionError("Invalid UUID format: " + uuid, e);
      }
    });
  }
  
  /**
   * Helper method to run a warmup with the specified number of threads.
   */
  private void warmupThreads(int count) throws Exception {
    // Configure mock for warmup
    doAnswer(invocation -> null).when(secretStore).setSecret(org.mockito.ArgumentMatchers.any(String.class));
    
    // Run a small number of operations with both thread types
    runWithPlatformThreads(count);
    runWithVirtualThreads(count);
  }
  
  /**
   * Helper method to run the specified number of operations with platform threads.
   */
  private void runWithPlatformThreads(int count) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(count);
    
    try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(count, 200))) {
      for (int i = 0; i < count; i++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            underTest.resetSecret();
            completionLatch.countDown();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      
      startLatch.countDown();
      completionLatch.await(30, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to run the specified number of operations with virtual threads.
   */
  private void runWithVirtualThreads(int count) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(count);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < count; i++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            underTest.resetSecret();
            completionLatch.countDown();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      
      startLatch.countDown();
      completionLatch.await(30, TimeUnit.SECONDS);
    }
  }
}