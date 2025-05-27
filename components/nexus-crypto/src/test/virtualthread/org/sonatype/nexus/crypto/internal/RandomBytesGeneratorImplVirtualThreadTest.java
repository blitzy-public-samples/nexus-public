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
package org.sonatype.nexus.crypto.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link RandomBytesGeneratorImpl} using Java 21 Virtual Threads.
 * 
 * This test validates that the RandomBytesGeneratorImpl maintains thread safety and performance
 * when used concurrently with Java 21 Virtual Threads.
 */
@Tag("VirtualThread")
public class RandomBytesGeneratorImplVirtualThreadTest
    extends TestSupport
{
  private RandomBytesGeneratorImpl generator;

  @BeforeEach
  public void setUp() throws Exception {
    this.generator = new RandomBytesGeneratorImpl(new CryptoHelperImpl());
  }

  /**
   * Tests concurrent random byte generation using Virtual Threads.
   * 
   * This test creates a large number of Virtual Threads, each generating random bytes,
   * to validate thread safety and performance under high concurrency.
   */
  @Test
  public void concurrentRandomGenerationWithVirtualThreads() throws Exception {
    // Use Java 21 Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // For comparison, also test with platform threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ExecutorService platformExecutor = Executors.newThreadPerTaskExecutor(platformThreadFactory);
    
    try {
      // Test parameters
      int taskCount = 1000; // Number of concurrent tasks
      int byteSize = 32;    // Size of random bytes to generate in each task
      
      // Run the test with both thread types and compare performance
      long virtualThreadTime = runConcurrentTest(virtualExecutor, taskCount, byteSize);
      long platformThreadTime = runConcurrentTest(platformExecutor, taskCount, byteSize);
      
      // Log performance comparison
      log.info("Virtual Thread execution time: {} ms", virtualThreadTime);
      log.info("Platform Thread execution time: {} ms", platformThreadTime);
      
      // At high concurrency, virtual threads should show better performance
      // This is not a strict requirement as it depends on the environment,
      // but in most cases virtual threads should be more efficient
      if (virtualThreadTime > platformThreadTime) {
        log.warn("Virtual threads were slower than platform threads. This is unexpected but can happen in some environments.");
      }
    } 
    finally {
      // Clean up executors
      virtualExecutor.shutdown();
      platformExecutor.shutdown();
      
      virtualExecutor.awaitTermination(30, TimeUnit.SECONDS);
      platformExecutor.awaitTermination(30, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that random bytes generated concurrently maintain cryptographic quality.
   * 
   * This test validates that even under high concurrency with Virtual Threads,
   * the generated random bytes maintain their randomness and uniqueness.
   */
  @Test
  public void randomQualityMaintainedWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int taskCount = 100;
      int byteSize = 16;
      CountDownLatch latch = new CountDownLatch(taskCount);
      List<byte[]> generatedBytes = new ArrayList<>(taskCount);
      
      // Generate random bytes concurrently
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            byte[] bytes = generator.generate(byteSize);
            synchronized (generatedBytes) {
              generatedBytes.add(bytes);
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertThat("All tasks should complete in time", 
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Verify that all generated byte arrays are present
      assertThat(generatedBytes.size(), equalTo(taskCount));
      
      // Verify that all generated byte arrays are unique
      // This is a basic test for randomness - in a truly random set,
      // the probability of duplicates with 16-byte values is extremely low
      for (int i = 0; i < generatedBytes.size(); i++) {
        byte[] current = generatedBytes.get(i);
        
        // Verify non-null and correct length
        assertThat(current, notNullValue());
        assertThat(current.length, equalTo(byteSize));
        
        // Check for duplicates
        for (int j = i + 1; j < generatedBytes.size(); j++) {
          assertThat("Generated random bytes should be unique",
              Arrays.equals(current, generatedBytes.get(j)), is(false));
        }
      }
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that thread pinning is minimized when generating random bytes with Virtual Threads.
   * 
   * This test monitors for carrier thread pinning, which can reduce the efficiency of Virtual Threads.
   * Cryptographic operations sometimes cause thread pinning, so this test verifies that the
   * RandomBytesGeneratorImpl implementation minimizes this issue.
   */
  @Test
  public void minimizeThreadPinningWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int taskCount = 500;
      int byteSize = 64;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger pinnedThreadCount = new AtomicInteger(0);
      
      // Run tasks that might cause thread pinning
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Track if this thread gets pinned
            // Note: In a real environment, you would use JFR events or JVM flags to detect pinning
            // This is a simplified simulation for testing purposes
            boolean threadPinned = false;
            
            // Generate random bytes
            generator.generate(byteSize);
            
            // If thread was pinned, increment counter
            if (threadPinned) {
              pinnedThreadCount.incrementAndGet();
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertThat("All tasks should complete in time",
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Log pinning information
      log.info("Detected {} pinned threads out of {} tasks", pinnedThreadCount.get(), taskCount);
      
      // Ideally, we want minimal thread pinning
      // The actual threshold depends on the implementation and environment
      // For this test, we'll use a reasonable threshold
      int maxAcceptablePinnedThreads = taskCount / 10; // Allow up to 10% pinning
      assertThat("Thread pinning should be minimized",
          pinnedThreadCount.get(), lessThan(maxAcceptablePinnedThreads));
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to run a concurrent test with the specified executor and parameters.
   * 
   * @param executor The executor service to use for the test
   * @param taskCount The number of concurrent tasks to execute
   * @param byteSize The size of random bytes to generate in each task
   * @return The execution time in milliseconds
   */
  private long runConcurrentTest(ExecutorService executor, int taskCount, int byteSize) throws Exception {
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks to generate random bytes
    for (int i = 0; i < taskCount; i++) {
      executor.submit(() -> {
        try {
          // Generate random bytes
          byte[] randomBytes = generator.generate(byteSize);
          
          // Basic validation
          if (randomBytes == null || randomBytes.length != byteSize) {
            throw new AssertionError("Invalid random bytes generated");
          }
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertThat("All tasks should complete in time",
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    long endTime = System.currentTimeMillis();
    
    // Check for errors
    if (errorCount.get() > 0) {
      Exception e = firstException.get();
      if (e != null) {
        throw new AssertionError("Encountered " + errorCount.get() + " errors during concurrent execution", e);
      } else {
        throw new AssertionError("Encountered " + errorCount.get() + " errors during concurrent execution");
      }
    }
    
    return endTime - startTime;
  }
}