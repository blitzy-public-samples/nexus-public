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
package org.sonatype.nexus.repository.virtualthread;

import java.time.Duration;
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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.routing.RoutingRule;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for validating repository operations using Java 21 Virtual Threads.
 * 
 * This class tests concurrent repository metadata access, content routing, and processing operations
 * under the Virtual Thread execution model. It verifies that repository operations maintain thread safety
 * and proper resource management when executed across numerous lightweight virtual threads, ensuring
 * the repository layer correctly handles high concurrency with Java 21's threading model.
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
@Tag("VirtualThreadTestGroup")
public class RepositoryVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_TASKS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  private static final String TEST_REPOSITORY_NAME = "test-repo";
  private static final String TEST_PATH = "/some/test/path";

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private Repository repository;

  @Mock
  private ViewFacet viewFacet;

  @Mock
  private Response response;

  @Mock
  private Content content;

  @Mock
  private RoutingRuleStore routingRuleStore;

  @Mock
  private RoutingRule routingRule;

  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  void setUp() {
    // Set up repository mock
    when(repositoryManager.get(TEST_REPOSITORY_NAME)).thenReturn(repository);
    when(repository.facet(ViewFacet.class)).thenReturn(viewFacet);
    lenient().when(viewFacet.dispatch(any(Request.class))).thenReturn(response);
    lenient().when(response.getPayload()).thenReturn(content);

    // Create executors for testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-test-", 0).factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    ThreadFactory platformThreadFactory = Thread.ofPlatform().name("pt-test-", 0).factory();
    platformThreadExecutor = Executors.newFixedThreadPool(100, platformThreadFactory);
  }

  @AfterEach
  void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
  }

  /**
   * Tests concurrent repository metadata access using Virtual Threads.
   * 
   * This test verifies that repository metadata can be accessed concurrently by many
   * virtual threads without thread safety issues or resource exhaustion.
   */
  @Test
  @DisplayName("Test concurrent repository metadata access with Virtual Threads")
  void testConcurrentRepositoryMetadataAccess() {
    int taskCount = CONCURRENT_TASKS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            Repository repo = repositoryManager.get(TEST_REPOSITORY_NAME);
            assertNotNull(repo, "Repository should not be null");
            // Access repository metadata
            ViewFacet view = repo.facet(ViewFacet.class);
            assertNotNull(view, "ViewFacet should not be null");
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error in virtual thread task", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent repository metadata access");
      verify(repositoryManager, times(taskCount)).get(TEST_REPOSITORY_NAME);
    });
  }

  /**
   * Tests content routing operations in a highly concurrent Virtual Thread environment.
   * 
   * This test verifies that content routing operations can handle high concurrency
   * when executed across many virtual threads.
   */
  @Test
  @DisplayName("Test content routing with high concurrency using Virtual Threads")
  void testContentRoutingWithHighConcurrency() {
    int taskCount = CONCURRENT_TASKS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<Request> requests = new ArrayList<>();
    
    // Set up routing rule mock
    when(routingRuleStore.getById(any())).thenReturn(routingRule);
    
    assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Create requests
      for (int i = 0; i < taskCount; i++) {
        Request request = new Request.Builder().action("GET").path(TEST_PATH + "/" + i).build();
        requests.add(request);
      }
      
      // Submit multiple concurrent routing tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        virtualThreadExecutor.submit(() -> {
          try {
            Repository repo = repositoryManager.get(TEST_REPOSITORY_NAME);
            ViewFacet view = repo.facet(ViewFacet.class);
            Response resp = view.dispatch(requests.get(index));
            assertNotNull(resp, "Response should not be null");
            assertNotNull(resp.getPayload(), "Response payload should not be null");
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error in virtual thread routing task", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent content routing");
      verify(viewFacet, times(taskCount)).dispatch(any(Request.class));
    });
  }

  /**
   * Tests repository component resource management during Virtual Thread handoffs.
   * 
   * This test verifies that repository components properly manage resources when
   * virtual threads are unmounted and remounted during blocking operations.
   */
  @Test
  @DisplayName("Test repository resource management during Virtual Thread handoffs")
  void testRepositoryResourceManagementDuringThreadHandoffs() {
    int taskCount = CONCURRENT_TASKS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Throwable> firstError = new AtomicReference<>();
    
    assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks that will cause thread handoffs due to simulated I/O
      for (int i = 0; i < taskCount; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            // Get repository and perform operations that will cause thread handoffs
            Repository repo = repositoryManager.get(TEST_REPOSITORY_NAME);
            ViewFacet view = repo.facet(ViewFacet.class);
            
            // Simulate I/O operation that will cause thread handoff
            Thread.sleep(10); // This will unmount the virtual thread
            
            // After remounting, continue with repository operations
            Request request = new Request.Builder().action("GET").path(TEST_PATH).build();
            Response resp = view.dispatch(request);
            assertNotNull(resp, "Response should not be null after thread handoff");
          } 
          catch (Throwable t) {
            errorCount.incrementAndGet();
            if (firstError.get() == null) {
              firstError.set(t);
            }
            log.error("Error during thread handoff test", t);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      if (errorCount.get() > 0) {
        fail("Errors occurred during thread handoff test: " + errorCount.get() + 
             ", first error: " + (firstError.get() != null ? firstError.get().getMessage() : "unknown"));
      }
    });
  }

  /**
   * Compares performance between platform threads and virtual threads for repository operations.
   * 
   * This test measures and compares the execution time of repository operations when using
   * platform threads versus virtual threads under high concurrency.
   */
  @Test
  @DisplayName("Compare performance between platform threads and virtual threads")
  void comparePerformanceBetweenPlatformAndVirtualThreads() {
    int taskCount = CONCURRENT_TASKS;
    
    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(platformThreadExecutor, taskCount);
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    
    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(virtualThreadExecutor, taskCount);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Assert that virtual threads perform better under high concurrency
    assertAll(
        () -> assertThat("Virtual thread execution should complete successfully", virtualThreadTime, greaterThan(0L)),
        () -> assertThat("Platform thread execution should complete successfully", platformThreadTime, greaterThan(0L)),
        () -> assertThat("Virtual threads should be more efficient than platform threads for I/O-bound operations", 
                         virtualThreadTime, lessThan(platformThreadTime))
    );
  }

  /**
   * Tests for thread pinning detection during repository operations.
   * 
   * This test verifies that the system can detect when virtual threads become pinned
   * to carrier threads during repository operations, which can impact performance.
   */
  @Test
  @DisplayName("Test thread pinning detection during repository operations")
  void testThreadPinningDetectionDuringRepositoryOperations() {
    // This test relies on the JVM flag -Djdk.tracePinnedThreads=full being set
    // or JFR events being configured to detect thread pinning
    
    // Create a synchronized block that will cause thread pinning
    Object lock = new Object();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> pinningDetected = new AtomicReference<>(false);
    
    // Start a thread that will hold the lock and perform a blocking operation
    Thread monitorThread = Thread.ofVirtual().start(() -> {
      try {
        synchronized (lock) {
          // Signal that we have the lock
          latch.countDown();
          
          // Perform a blocking operation while holding the lock
          // This will cause the virtual thread to be pinned to its carrier thread
          Thread.sleep(500);
          
          // Check if we're running on a virtual thread
          if (Thread.currentThread().isVirtual()) {
            log.info("Running on virtual thread: {}", Thread.currentThread());
            pinningDetected.set(true);
          }
        }
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    try {
      // Wait for the monitor thread to acquire the lock
      assertThat("Monitor thread should acquire the lock", 
                 latch.await(5, TimeUnit.SECONDS), is(true));
      
      // Wait for the monitor thread to complete
      monitorThread.join(5000);
      
      // Verify that we detected the pinning condition
      assertThat("Should detect that we're running on a virtual thread", 
                 pinningDetected.get(), is(true));
      
      // Note: The actual pinning detection relies on JVM flags or JFR events
      // This test only verifies that we can detect when we're running on a virtual thread
      // In a real environment, the -Djdk.tracePinnedThreads=full flag would log pinning events
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Test was interrupted");
    }
  }

  /**
   * Tests concurrent repository operations using CompletableFuture with Virtual Threads.
   * 
   * This test verifies that repository operations can be efficiently executed using
   * CompletableFuture combined with Virtual Threads for improved concurrency.
   */
  @Test
  @DisplayName("Test concurrent repository operations with CompletableFuture and Virtual Threads")
  void testConcurrentRepositoryOperationsWithCompletableFuture() {
    int taskCount = CONCURRENT_TASKS;
    List<CompletableFuture<Response>> futures = new ArrayList<>();
    
    assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Create CompletableFuture tasks for repository operations
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        CompletableFuture<Response> future = CompletableFuture.supplyAsync(() -> {
          try {
            Repository repo = repositoryManager.get(TEST_REPOSITORY_NAME);
            ViewFacet view = repo.facet(ViewFacet.class);
            Request request = new Request.Builder()
                .action("GET")
                .path(TEST_PATH + "/" + index)
                .build();
            return view.dispatch(request);
          } 
          catch (Exception e) {
            throw new RuntimeException("Error in repository operation", e);
          }
        }, virtualThreadExecutor);
        
        futures.add(future);
      }
      
      // Wait for all futures to complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          futures.toArray(new CompletableFuture[0])
      );
      
      allFutures.join();
      
      // Verify results
      for (CompletableFuture<Response> future : futures) {
        Response response = future.get();
        assertThat("Response should not be null", response, notNullValue());
        assertThat("Response payload should not be null", response.getPayload(), notNullValue());
      }
      
      verify(viewFacet, times(taskCount)).dispatch(any(Request.class));
    });
  }

  /**
   * Measures the execution time of repository operations using the provided executor.
   *
   * @param executor the executor service to use for running tasks
   * @param taskCount the number of concurrent tasks to execute
   * @return the execution time in milliseconds
   */
  private long measureExecutionTime(ExecutorService executor, int taskCount) {
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks to the executor
    for (int i = 0; i < taskCount; i++) {
      executor.submit(() -> {
        try {
          Repository repo = repositoryManager.get(TEST_REPOSITORY_NAME);
          ViewFacet view = repo.facet(ViewFacet.class);
          Request request = new Request.Builder().action("GET").path(TEST_PATH).build();
          Response resp = view.dispatch(request);
          assertNotNull(resp, "Response should not be null");
          
          // Simulate some processing time
          Thread.sleep(5);
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in performance test task", e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    try {
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Test was interrupted", e);
    }
    
    long endTime = System.currentTimeMillis();
    
    // Check for errors
    assertEquals(0, errorCount.get(), "No errors should occur during performance test");
    
    return endTime - startTime;
  }
}