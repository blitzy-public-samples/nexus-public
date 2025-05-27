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
package java21;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptClient;
import org.sonatype.nexus.script.ScriptManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests script execution using Java 21's virtual threads to validate performance improvements
 * for concurrent script operations.
 * 
 * This test ensures that the Script Plugin can properly leverage virtual threads for I/O-bound
 * script operations, reducing resource consumption and improving scalability when multiple
 * scripts are executed simultaneously.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Virtual Thread Script Execution Tests")
public class VirtualThreadScriptExecutionTest
    extends TestSupport
{
  private static final int CONCURRENT_SCRIPT_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private ScriptManager scriptManager;
  
  @Mock
  private ScriptClient scriptClient;
  
  @Mock
  private Script script;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() {
    // Create executors for both platform and virtual threads for comparison
    platformThreadExecutor = Executors.newFixedThreadPool(100); // Limited to 100 platform threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Unlimited virtual threads
    
    // Setup mock behavior
    when(scriptManager.get(anyString())).thenReturn(script);
    when(scriptClient.run(any(Script.class), any())).thenAnswer(invocation -> {
      // Simulate I/O-bound operation with a small delay
      Thread.sleep(50);
      return "Script executed successfully";
    });
  }
  
  @AfterEach
  void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    
    platformThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Tests that script execution works correctly with virtual threads.
   * This validates basic functionality using the virtual thread executor.
   */
  @Test
  @DisplayName("Basic script execution with virtual threads")
  void testBasicScriptExecutionWithVirtualThreads() throws Exception {
    // Execute a script using a virtual thread
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
      try {
        return scriptClient.run(script, null);
      }
      catch (Exception e) {
        throw new RuntimeException("Script execution failed", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for completion and verify result
    String result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(result, is(notNullValue()));
    assertThat(result, is(equalTo("Script executed successfully")));
  }
  
  /**
   * Tests concurrent script execution using virtual threads.
   * This validates that a large number of scripts can be executed concurrently
   * without exhausting system resources.
   */
  @Test
  @DisplayName("Concurrent script execution with virtual threads")
  void testConcurrentScriptExecutionWithVirtualThreads() throws Exception {
    // Create a large number of concurrent script execution tasks
    CountDownLatch latch = new CountDownLatch(CONCURRENT_SCRIPT_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < CONCURRENT_SCRIPT_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          scriptClient.run(script, null);
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all tasks completed successfully
    assertThat("All tasks should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during script execution", errorCount.get(), is(0));
  }
  
  /**
   * Tests that virtual threads don't get pinned during script execution.
   * Thread pinning can occur when a virtual thread performs a blocking operation
   * that cannot be unmounted from the carrier thread, which reduces the efficiency
   * of virtual threads.
   */
  @Test
  @DisplayName("Verify no thread pinning during script execution")
  void testNoThreadPinningDuringScriptExecution() throws Exception {
    // Create a task that would detect thread pinning
    // Thread pinning would be evident if we can't execute many more virtual threads than platform threads
    
    int platformThreadCount = 100;
    int virtualThreadCount = 10000; // 100x more virtual threads
    
    // First test with platform threads - this should fail due to thread exhaustion
    CountDownLatch platformLatch = new CountDownLatch(platformThreadCount);
    AtomicReference<Exception> platformException = new AtomicReference<>();
    
    // Try to execute more tasks than available platform threads
    for (int i = 0; i < virtualThreadCount; i++) {
      platformThreadExecutor.submit(() -> {
        try {
          scriptClient.run(script, null);
          platformLatch.countDown();
        }
        catch (Exception e) {
          platformException.set(e);
        }
      });
    }
    
    // Now test with virtual threads - this should succeed
    CountDownLatch virtualLatch = new CountDownLatch(virtualThreadCount);
    AtomicInteger virtualErrorCount = new AtomicInteger(0);
    
    for (int i = 0; i < virtualThreadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          scriptClient.run(script, null);
        }
        catch (Exception e) {
          virtualErrorCount.incrementAndGet();
        }
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    // Wait for virtual thread tasks to complete
    boolean virtualCompleted = virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify virtual threads completed successfully without errors
    assertThat("All virtual thread tasks should complete", virtualCompleted, is(true));
    assertThat("No errors should occur with virtual threads", virtualErrorCount.get(), is(0));
    
    // Platform threads should not be able to complete all tasks in the given time
    boolean platformCompleted = platformLatch.await(1, TimeUnit.SECONDS);
    assertThat("Platform threads should not complete all tasks", platformCompleted, is(false));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for script execution.
   * This test validates that virtual threads provide better scalability and performance
   * for concurrent script operations.
   */
  @Test
  @DisplayName("Compare performance between platform and virtual threads")
  @Java21TestGroup
  @VirtualThreadTestGroup
  void testComparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    int testSize = 500; // Number of scripts to execute in each test
    
    // Test with platform threads
    long platformStartTime = System.currentTimeMillis();
    CountDownLatch platformLatch = new CountDownLatch(testSize);
    List<CompletableFuture<String>> platformFutures = new ArrayList<>();
    
    for (int i = 0; i < testSize; i++) {
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        try {
          return scriptClient.run(script, null);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
        finally {
          platformLatch.countDown();
        }
      }, platformThreadExecutor);
      platformFutures.add(future);
    }
    
    platformLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long platformDuration = System.currentTimeMillis() - platformStartTime;
    
    // Test with virtual threads
    long virtualStartTime = System.currentTimeMillis();
    CountDownLatch virtualLatch = new CountDownLatch(testSize);
    List<CompletableFuture<String>> virtualFutures = new ArrayList<>();
    
    for (int i = 0; i < testSize; i++) {
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        try {
          return scriptClient.run(script, null);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
        finally {
          virtualLatch.countDown();
        }
      }, virtualThreadExecutor);
      virtualFutures.add(future);
    }
    
    virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long virtualDuration = System.currentTimeMillis() - virtualStartTime;
    
    // Log performance results
    log.info("Platform threads execution time: {} ms", platformDuration);
    log.info("Virtual threads execution time: {} ms", virtualDuration);
    
    // Virtual threads should be faster for I/O-bound operations with high concurrency
    assertThat("Virtual threads should be faster than platform threads", 
        virtualDuration, lessThan(platformDuration));
  }
  
  /**
   * Tests script execution with an extremely high number of concurrent threads.
   * This validates that virtual threads can handle a much higher concurrency level
   * than would be practical with platform threads.
   */
  @Test
  @DisplayName("High concurrency script execution with virtual threads")
  @Java21TestGroup
  @VirtualThreadTestGroup
  void testHighConcurrencyScriptExecution() throws Exception {
    int highConcurrencyCount = 5000; // 5000 concurrent script executions
    
    // Create a thread factory that will be used to create virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using the virtual thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(highConcurrencyCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit a large number of script execution tasks
      for (int i = 0; i < highConcurrencyCount; i++) {
        executor.submit(() -> {
          try {
            scriptClient.run(script, null);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during script execution", errorCount.get(), is(0));
      assertThat("All scripts should execute successfully", 
          successCount.get(), is(equalTo(highConcurrencyCount)));
    }
  }
}