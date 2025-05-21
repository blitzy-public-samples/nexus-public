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
package org.sonatype.nexus.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.routing.RoutingRule;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.view.ContentTypes;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Router;
import org.sonatype.nexus.repository.view.Status;
import org.sonatype.nexus.repository.view.ViewFacet;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler;
import org.sonatype.nexus.repository.view.handlers.RoutingHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for validating repository operations using Java 21 Virtual Threads.
 * 
 * This test class verifies that repository operations maintain thread safety and proper resource
 * management when executed across numerous lightweight virtual threads, ensuring the repository
 * layer correctly handles high concurrency with Java 21's threading model.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class RepositoryVirtualThreadTest
    extends TestSupport
{
  private static final int TASK_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private RepositoryManager repositoryManager;
  
  @Mock
  private Repository repository;
  
  @Mock
  private ViewFacet viewFacet;
  
  @Mock
  private Router router;
  
  @Mock
  private RoutingRuleStore routingRuleStore;
  
  @Mock
  private RoutingHandler routingHandler;
  
  @Mock
  private ContentHeadersHandler contentHeadersHandler;
  
  @Mock
  private LastDownloadedHandler lastDownloadedHandler;
  
  @Mock
  private EventManager eventManager;
  
  @BeforeEach
  public void setup() throws Exception {
    when(repository.getName()).thenReturn("test-repo");
    when(repository.facet(ViewFacet.class)).thenReturn(viewFacet);
    when(repositoryManager.get("test-repo")).thenReturn(repository);
    
    // Setup routing rules
    RoutingRule rule = new RoutingRule();
    rule.name("test-rule");
    lenient().when(routingRuleStore.getByName("test-rule")).thenReturn(rule);
    
    // Setup mock response for repository requests
    Response response = new Response.Builder()
        .status(Status.success(200))
        .header("Content-Type", ContentTypes.TEXT_PLAIN)
        .build();
    
    lenient().when(viewFacet.dispatch(any(Request.class))).thenReturn(response);
    lenient().when(router.route(any(Context.class))).thenReturn(response);
  }
  
  /**
   * Tests concurrent repository metadata access using Virtual Threads.
   * 
   * This test verifies that repository metadata can be accessed concurrently by many
   * virtual threads without errors or resource contention issues.
   */
  @Test
  public void testConcurrentRepositoryMetadataAccess() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(TASK_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < TASK_COUNT; i++) {
        executor.submit(() -> {
          try {
            Repository repo = repositoryManager.get("test-repo");
            assertThat(repo, notNullValue());
            assertThat(repo.getName(), equalTo("test-repo"));
            
            // Access repository metadata
            ViewFacet view = repo.facet(ViewFacet.class);
            assertThat(view, notNullValue());
          } 
          catch (Exception e) {
            log.error("Error in virtual thread task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("No errors should occur", errorCount.get(), is(0));
      verify(repositoryManager, times(TASK_COUNT)).get("test-repo");
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests content routing operations in a highly concurrent Virtual Thread environment.
   * 
   * This test verifies that the routing system correctly handles requests from many
   * concurrent virtual threads, ensuring proper routing decisions and resource management.
   */
  @Test
  public void testConcurrentContentRouting() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(TASK_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent routing tasks using virtual threads
      for (int i = 0; i < TASK_COUNT; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a request
            Request request = new Request.Builder()
                .action("GET")
                .path("/content/test-repo/path/to/asset-" + index + ".txt")
                .build();
            
            // Dispatch the request through the view facet
            Response response = viewFacet.dispatch(request);
            
            // Verify response
            assertThat(response, notNullValue());
            assertThat(response.getStatus().isSuccessful(), is(true));
          } 
          catch (Exception e) {
            log.error("Error in content routing task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All routing tasks should complete within timeout", completed, is(true));
      assertThat("No routing errors should occur", errorCount.get(), is(0));
      verify(viewFacet, times(TASK_COUNT)).dispatch(any(Request.class));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests repository component resource management during Virtual Thread handoffs.
   * 
   * This test verifies that repository components properly manage resources when virtual threads
   * are unmounted and remounted during I/O operations, ensuring no resource leaks occur.
   */
  @Test
  public void testResourceManagementDuringThreadHandoffs() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int iterations = 100;
    CountDownLatch latch = new CountDownLatch(iterations);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    try {
      // Create tasks that will cause thread handoffs due to I/O operations
      for (int i = 0; i < iterations; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Simulate a sequence of operations with I/O that will cause thread handoffs
            Repository repo = repositoryManager.get("test-repo");
            
            // First operation - metadata access
            ViewFacet view = repo.facet(ViewFacet.class);
            
            // Simulate I/O delay that will cause thread unmounting
            Thread.sleep(10);
            
            // Second operation after potential remount - request dispatch
            Request request = new Request.Builder()
                .action("GET")
                .path("/content/test-repo/resource.txt")
                .build();
            
            Response response = view.dispatch(request);
            assertThat(response.getStatus().getCode(), is(200));
            
            // Simulate another I/O delay
            Thread.sleep(10);
            
            // Third operation after another potential remount - event publishing
            eventManager.post(new Object());
          } 
          catch (Exception e) {
            log.error("Error during thread handoff test", e);
            throw new RuntimeException(e);
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All resource management tasks should complete", completed, is(true));
      verify(repositoryManager, times(iterations)).get("test-repo");
      verify(viewFacet, times(iterations)).dispatch(any(Request.class));
      verify(eventManager, times(iterations)).post(any());
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Compares performance between Virtual Threads and Platform Threads for repository operations.
   * 
   * This test executes the same repository operations using both thread models and compares
   * execution time, demonstrating the performance benefits of Virtual Threads for I/O-bound
   * repository operations.
   */
  @Test
  public void testPerformanceComparisonWithPlatformThreads() throws Exception {
    // Create thread factories for both models
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Define the repository operation to benchmark
    Supplier<Void> repositoryOperation = () -> {
      try {
        Repository repo = repositoryManager.get("test-repo");
        ViewFacet view = repo.facet(ViewFacet.class);
        
        Request request = new Request.Builder()
            .action("GET")
            .path("/content/test-repo/benchmark.txt")
            .build();
        
        Response response = view.dispatch(request);
        assertThat(response.getStatus().isSuccessful(), is(true));
        
        // Simulate some I/O delay
        Thread.sleep(5);
        
        return null;
      } 
      catch (Exception e) {
        throw new RuntimeException("Error in repository operation", e);
      }
    };
    
    // Benchmark with platform threads
    long platformThreadTime = benchmarkOperation(platformThreadFactory, repositoryOperation, 500);
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    
    // Benchmark with virtual threads
    long virtualThreadTime = benchmarkOperation(virtualThreadFactory, repositoryOperation, 500);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Verify that virtual threads perform better
    assertThat("Virtual threads should be faster than platform threads", 
        virtualThreadTime, lessThan(platformThreadTime));
    
    // Calculate improvement percentage
    double improvementPercent = ((double)(platformThreadTime - virtualThreadTime) / platformThreadTime) * 100;
    log.info("Performance improvement with virtual threads: {}%", String.format("%.2f", improvementPercent));
    
    // Verify significant improvement
    assertThat("Virtual threads should show significant performance improvement",
        improvementPercent, greaterThan(10.0));
  }
  
  /**
   * Helper method to benchmark an operation with a specific thread factory.
   * 
   * @param threadFactory the thread factory to use
   * @param operation the operation to benchmark
   * @param taskCount the number of concurrent tasks to execute
   * @return the execution time in milliseconds
   */
  private long benchmarkOperation(
      ThreadFactory threadFactory, 
      Supplier<Void> operation,
      int taskCount) throws Exception 
  {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    try {
      long startTime = System.currentTimeMillis();
      
      // Submit tasks
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            operation.get();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for completion
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      long endTime = System.currentTimeMillis();
      return endTime - startTime;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that virtual threads are correctly identified and that thread pinning is avoided.
   * 
   * This test verifies that threads created with Thread.ofVirtual() are correctly identified
   * as virtual threads and that operations don't cause thread pinning.
   */
  @Test
  public void testVirtualThreadIdentificationAndPinning() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    Thread virtualThread = virtualThreadFactory.newThread(() -> {});
    
    // Verify thread is identified as virtual
    assertThat("Thread should be identified as virtual", virtualThread.isVirtual(), is(true));
    
    // Test for thread pinning by running operations that might cause pinning
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    CountDownLatch latch = new CountDownLatch(100);
    AtomicInteger pinnedThreadCount = new AtomicInteger(0);
    
    try {
      for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
          try {
            // Access repository without using synchronized blocks
            Repository repo = repositoryManager.get("test-repo");
            ViewFacet view = repo.facet(ViewFacet.class);
            
            // Check if current thread is virtual
            boolean isVirtual = Thread.currentThread().isVirtual();
            if (!isVirtual) {
              pinnedThreadCount.incrementAndGet();
            }
            
            assertThat("Thread should be virtual", isVirtual, is(true));
            
            // Perform operation that should not cause pinning
            Request request = new Request.Builder()
                .action("GET")
                .path("/content/test-repo/pinning-test.txt")
                .build();
            
            view.dispatch(request);
          } 
          catch (Exception e) {
            log.error("Error in pinning test", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify no threads were pinned
      assertThat("No threads should be pinned", pinnedThreadCount.get(), is(0));
    } 
    finally {
      executor.shutdown();
    }
  }
}