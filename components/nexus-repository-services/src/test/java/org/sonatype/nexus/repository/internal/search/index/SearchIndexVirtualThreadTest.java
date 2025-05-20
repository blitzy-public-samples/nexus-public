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
package org.sonatype.nexus.repository.internal.search.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.search.index.ElasticSearchIndexService;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.testcommon.Java21TestGroup;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers.isNotPinned;
import static org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers.isVirtualThread;

/**
 * Tests for search index operations using Java 21 virtual threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(Java21TestGroup.class)
public class SearchIndexVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int OPERATION_DELAY_MS = 10;
  
  @Mock
  private ElasticSearchIndexService searchIndexService;
  
  @Mock
  private Repository repository;
  
  @Mock
  private SearchIndexFacet searchIndexFacet;
  
  private ExecutorService virtualThreadExecutor;
  
  private ThreadPinningDetector pinningDetector;
  
  @BeforeEach
  void setUp() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    pinningDetector = new ThreadPinningDetector();
    
    when(repository.facet(SearchIndexFacet.class)).thenReturn(searchIndexFacet);
    when(repository.getName()).thenReturn("test-repo");
    
    // Simulate I/O operations with a small delay
    doAnswer(invocation -> {
      Thread.sleep(OPERATION_DELAY_MS);
      return null;
    }).when(searchIndexService).flush();
    
    doAnswer(invocation -> {
      Thread.sleep(OPERATION_DELAY_MS);
      return true;
    }).when(searchIndexService).indexExist(any(Repository.class));
  }
  
  @AfterEach
  void tearDown() {
    virtualThreadExecutor.shutdown();
    try {
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      virtualThreadExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Test that search index operations can be executed concurrently using virtual threads.
   */
  @Test
  void testConcurrentSearchOperationsWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger completedOperations = new AtomicInteger(0);
    
    // Submit multiple concurrent operations
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be ready
          
          // Verify this is running on a virtual thread
          Thread currentThread = Thread.currentThread();
          assertThat(currentThread, isVirtualThread());
          
          // Perform search index operation
          boolean exists = searchIndexService.indexExist(repository);
          assertTrue(exists);
          
          completedOperations.incrementAndGet();
        } catch (Exception e) {
          log.error("Error in virtual thread operation", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all tasks simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete
    assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Not all operations completed in time");
    assertThat(completedOperations.get(), equalTo(CONCURRENT_OPERATIONS));
    
    // Verify the search index service was called the expected number of times
    verify(searchIndexService, times(CONCURRENT_OPERATIONS)).indexExist(repository);
  }
  
  /**
   * Test that search index operations don't cause thread pinning when using virtual threads.
   */
  @Test
  void testSearchOperationsAvoidThreadPinning() throws Exception {
    // Start pinning detection
    pinningDetector.start();
    
    try {
      // Perform search index operations that involve I/O
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        // Verify this is running on a virtual thread
        Thread currentThread = Thread.currentThread();
        assertThat(currentThread, isVirtualThread());
        
        // Perform search index operation with I/O
        searchIndexService.flush();
        
        // Verify the thread is not pinned
        assertThat(currentThread, isNotPinned());
      }, virtualThreadExecutor);
      
      // Wait for the operation to complete
      assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
      
      // Verify the search index service was called
      verify(searchIndexService).flush();
      
      // Check if any pinning was detected
      assertFalse(pinningDetector.wasPinningDetected(), 
          "Thread pinning detected during search operations");
    } finally {
      pinningDetector.stop();
    }
  }
  
  /**
   * Test pattern matching for handling different search index scenarios.
   */
  @Test
  void testPatternMatchingForSearchIndexScenarios() {
    // Define different types of search index operations
    record IndexOperation(String type, Repository repository) {}
    
    List<IndexOperation> operations = List.of(
        new IndexOperation("create", repository),
        new IndexOperation("update", repository),
        new IndexOperation("delete", repository),
        new IndexOperation("rebuild", repository)
    );
    
    // Process operations using pattern matching
    for (IndexOperation operation : operations) {
      String result = switch (operation) {
        case IndexOperation(String type, Repository repo) when type.equals("create") ->
          "Creating index for " + repo.getName();
        case IndexOperation(String type, Repository repo) when type.equals("update") ->
          "Updating index for " + repo.getName();
        case IndexOperation(String type, Repository repo) when type.equals("delete") ->
          "Deleting index for " + repo.getName();
        case IndexOperation(String type, Repository repo) when type.equals("rebuild") ->
          "Rebuilding index for " + repo.getName();
        default -> "Unknown operation";
      };
      
      assertThat(result, notNullValue());
      assertTrue(result.contains(repository.getName()));
      assertTrue(result.contains(operation.type()));
    }
  }
  
  /**
   * Test asynchronous search operations using CompletableFuture with virtual threads.
   */
  @Test
  void testAsyncSearchOperationsWithVirtualThreads() throws Exception {
    int operationCount = 10;
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    // Submit multiple async operations
    for (int i = 0; i < operationCount; i++) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        // Verify this is running on a virtual thread
        Thread currentThread = Thread.currentThread();
        assertThat(currentThread, isVirtualThread());
        
        // Perform search index operation
        return searchIndexService.indexExist(repository);
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all futures to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0])
    );
    
    assertDoesNotThrow(() -> allFutures.get(5, TimeUnit.SECONDS));
    
    // Verify all operations succeeded
    for (CompletableFuture<Boolean> future : futures) {
      assertTrue(future.isDone());
      assertTrue(future.get());
    }
    
    // Verify the search index service was called the expected number of times
    verify(searchIndexService, times(operationCount)).indexExist(repository);
  }
  
  /**
   * Test performance comparison between platform threads and virtual threads for search operations.
   */
  @Test
  void testPerformanceComparisonWithVirtualThreads() throws Exception {
    int operationCount = 1000;
    
    // Measure time with platform threads
    long platformThreadStart = System.currentTimeMillis();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(20)) {
      CountDownLatch platformLatch = new CountDownLatch(operationCount);
      
      for (int i = 0; i < operationCount; i++) {
        platformExecutor.submit(() -> {
          try {
            searchIndexService.indexExist(repository);
          } finally {
            platformLatch.countDown();
          }
        });
      }
      
      platformLatch.await(30, TimeUnit.SECONDS);
    }
    long platformThreadTime = System.currentTimeMillis() - platformThreadStart;
    
    // Measure time with virtual threads
    long virtualThreadStart = System.currentTimeMillis();
    CountDownLatch virtualLatch = new CountDownLatch(operationCount);
    
    for (int i = 0; i < operationCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          searchIndexService.indexExist(repository);
        } finally {
          virtualLatch.countDown();
        }
      });
    }
    
    virtualLatch.await(30, TimeUnit.SECONDS);
    long virtualThreadTime = System.currentTimeMillis() - virtualThreadStart;
    
    // Log the results
    log.info("Platform thread time: {} ms", platformThreadTime);
    log.info("Virtual thread time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound operations
    // This is a soft assertion as the actual performance depends on the environment
    assertThat("Virtual threads should be comparable or faster than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
  }
}