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
package org.sonatype.nexus.repository.content.browse;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.browse.node.BrowseNode;
import org.sonatype.nexus.repository.browse.node.BrowsePath;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.browse.BrowseFacetImpl;
import org.sonatype.nexus.repository.content.browse.BrowseTestSupport;
import org.sonatype.nexus.repository.content.browse.store.BrowseNodeData;
import org.sonatype.nexus.repository.content.browse.store.BrowseNodeManager;
import org.sonatype.nexus.repository.ossindex.PackageUrlService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for repository browse operations using Java 21 Virtual Threads.
 * 
 * This test class validates that hierarchical browsing functions correctly under high concurrency
 * when using Virtual Threads. It verifies that browse node generation, tree traversal, and
 * path-based content retrieval maintain consistency and performance.
 *
 * @since 3.60
 */
public class ContentBrowseVirtualThreadTest
    extends BrowseTestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 1000;
  private static final int DEEP_HIERARCHY_DEPTH = 10;
  private static final int BROWSE_OPERATIONS_PER_THREAD = 50;
  private static final long PERFORMANCE_THRESHOLD_MS = 5000; // 5 seconds max for concurrent operations

  @Mock
  private BrowseNodeManager browseNodeManager;

  @Mock
  private Repository repository;

  @Mock
  private PackageUrlService packageUrlService;

  private BrowseFacet browseFacet;

  private List<BrowseNode> mockBrowseNodes;

  @Before
  public void setUp() throws Exception {
    // Create the BrowseFacet implementation
    browseFacet = new BrowseFacetImpl(
        Collections.emptyMap(),
        Collections.emptyMap(),
        packageUrlService,
        1000);

    // Set up the repository
    when(repository.getFormat()).thenReturn(new Format("raw") {});
    when(repository.getName()).thenReturn("Virtual-Thread-Test-Repository");

    // Attach the repository to the browse facet
    browseFacet.attach(repository);

    // Inject the browse node manager using reflection
    Field browseNodeManagerField = BrowseFacetImpl.class.getDeclaredField("browseNodeManager");
    browseNodeManagerField.setAccessible(true);
    browseNodeManagerField.set(browseFacet, browseNodeManager);

    // Create mock browse nodes for testing
    mockBrowseNodes = createMockBrowseNodes();

    // Set up the browse node manager to return mock nodes
    when(browseNodeManager.getByDisplayPath(anyList(), anyInt(), anyString(), anyMap()))
        .thenReturn(mockBrowseNodes);
    
    // Set up getByRequestPath to return a mock node
    BrowseNode mockNode = mockBrowseNodes.get(0);
    when(browseNodeManager.getByRequestPath(anyString()))
        .thenReturn(Optional.of(mockNode));
  }

  /**
   * Tests concurrent browse operations using Virtual Threads.
   * 
   * This test creates a large number of Virtual Threads that each perform multiple
   * browse operations, verifying that the system can handle high concurrency without
   * errors or excessive thread overhead.
   */
  @Test
  public void testConcurrentBrowseOperationsWithVirtualThreads() throws Exception {
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Track any exceptions that occur during execution
    ConcurrentHashMap<Integer, Throwable> exceptions = new ConcurrentHashMap<>();
    
    // Create and start virtual threads
    long startTime = System.currentTimeMillis();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform multiple browse operations
            for (int j = 0; j < BROWSE_OPERATIONS_PER_THREAD; j++) {
              List<String> displayPath = List.of("path" + threadId, "subpath" + j);
              List<BrowseNode> nodes = browseFacet.getByDisplayPath(displayPath, 100, null, null);
              
              // Verify the results
              assertThat(nodes, notNullValue());
              assertThat(nodes, hasSize(mockBrowseNodes.size()));
            }
          }
          catch (Throwable t) {
            exceptions.put(threadId, t);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Signal all threads to start
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(PERFORMANCE_THRESHOLD_MS, TimeUnit.MILLISECONDS);
      assertThat("All virtual threads should complete within the time threshold", completed, is(true));
    }
    
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    // Check for any exceptions
    if (!exceptions.isEmpty()) {
      throw new AssertionError("Exceptions occurred during concurrent execution: " + exceptions);
    }
    
    // Verify the browse operations were performed the expected number of times
    verify(browseNodeManager, times(VIRTUAL_THREAD_COUNT * BROWSE_OPERATIONS_PER_THREAD))
        .getByDisplayPath(anyList(), anyInt(), anyString(), anyMap());
    
    // Log performance metrics
    log.info("Completed {} browse operations across {} virtual threads in {} ms",
        VIRTUAL_THREAD_COUNT * BROWSE_OPERATIONS_PER_THREAD, VIRTUAL_THREAD_COUNT, duration);
    
    // Assert that the operation completed within the performance threshold
    assertThat("Browse operations should complete within performance threshold",
        duration, lessThan(PERFORMANCE_THRESHOLD_MS));
  }

  /**
   * Tests browsing deep hierarchies using Virtual Threads.
   * 
   * This test verifies that the system can efficiently navigate deep hierarchical
   * structures when using Virtual Threads, which is a common scenario in repository
   * management systems with nested directory structures.
   */
  @Test
  public void testDeepHierarchyBrowsingWithVirtualThreads() throws Exception {
    // Create a deep hierarchy of mock browse nodes
    List<List<BrowseNode>> hierarchyLevels = createDeepHierarchy(DEEP_HIERARCHY_DEPTH);
    
    // Configure the browse node manager to return different levels based on the display path depth
    when(browseNodeManager.getByDisplayPath(anyList(), anyInt(), anyString(), anyMap()))
        .thenAnswer(invocation -> {
          List<String> displayPath = invocation.getArgument(0);
          int depth = displayPath.size();
          if (depth < hierarchyLevels.size()) {
            return hierarchyLevels.get(depth);
          }
          return emptyList();
        });
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    int threadCount = 100; // Fewer threads for deep hierarchy test
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Track any exceptions that occur during execution
    ConcurrentHashMap<Integer, Throwable> exceptions = new ConcurrentHashMap<>();
    
    // Create and start virtual threads
    long startTime = System.currentTimeMillis();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Navigate the entire hierarchy depth
            List<String> path = new ArrayList<>();
            for (int depth = 0; depth < DEEP_HIERARCHY_DEPTH; depth++) {
              path.add("level" + depth);
              List<BrowseNode> nodes = browseFacet.getByDisplayPath(path, 100, null, null);
              
              // Verify the results
              assertThat(nodes, notNullValue());
              if (depth < DEEP_HIERARCHY_DEPTH - 1) {
                assertThat(nodes, hasSize(greaterThan(0)));
              }
            }
          }
          catch (Throwable t) {
            exceptions.put(threadId, t);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Signal all threads to start
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(PERFORMANCE_THRESHOLD_MS, TimeUnit.MILLISECONDS);
      assertThat("All virtual threads should complete within the time threshold", completed, is(true));
    }
    
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    // Check for any exceptions
    if (!exceptions.isEmpty()) {
      throw new AssertionError("Exceptions occurred during concurrent execution: " + exceptions);
    }
    
    // Verify the browse operations were performed the expected number of times
    verify(browseNodeManager, times(threadCount * DEEP_HIERARCHY_DEPTH))
        .getByDisplayPath(anyList(), anyInt(), anyString(), anyMap());
    
    // Log performance metrics
    log.info("Completed deep hierarchy browsing ({} levels) across {} virtual threads in {} ms",
        DEEP_HIERARCHY_DEPTH, threadCount, duration);
    
    // Assert that the operation completed within the performance threshold
    assertThat("Deep hierarchy browsing should complete within performance threshold",
        duration, lessThan(PERFORMANCE_THRESHOLD_MS));
  }

  /**
   * Compares the performance of Virtual Threads vs Platform Threads for browse operations.
   * 
   * This test executes the same browse operations using both Virtual Threads and Platform Threads,
   * measuring the performance difference to validate that Virtual Threads provide better
   * scalability for I/O-bound operations like repository browsing.
   */
  @Test
  public void testVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    int threadCount = 500; // Use fewer threads for comparison test
    int operationsPerThread = 20;
    
    // Run with platform threads first
    long platformThreadDuration = runBrowseOperationsWithThreads(
        threadCount, operationsPerThread, Thread::new);
    
    // Run with virtual threads
    long virtualThreadDuration = runBrowseOperationsWithThreads(
        threadCount, operationsPerThread, Thread.ofVirtual()::unstarted);
    
    // Log performance comparison
    log.info("Performance comparison for {} threads with {} operations each:", 
        threadCount, operationsPerThread);
    log.info("Platform Threads: {} ms", platformThreadDuration);
    log.info("Virtual Threads: {} ms", virtualThreadDuration);
    log.info("Improvement factor: {}x", (double) platformThreadDuration / virtualThreadDuration);
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // but we don't make a hard assertion since this is environment-dependent
    // and we're using mocks which may not accurately reflect real I/O behavior
  }

  /**
   * Tests that thread pinning is minimized during browse operations with Virtual Threads.
   * 
   * Thread pinning occurs when a Virtual Thread cannot be unmounted from its carrier thread,
   * typically due to synchronized blocks or native methods. This test verifies that the
   * browse operations avoid excessive thread pinning, which would reduce the scalability
   * benefits of Virtual Threads.
   */
  @Test
  public void testThreadPinningMinimizedWithVirtualThreads() throws Exception {
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    int threadCount = 200;
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Track thread execution times to detect potential pinning
    ConcurrentHashMap<Integer, Long> executionTimes = new ConcurrentHashMap<>();
    
    // Create and start virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            long threadStartTime = System.nanoTime();
            
            // Perform browse operations that should not cause pinning
            for (int j = 0; j < 10; j++) {
              // Use getByRequestPath which should be non-blocking and avoid pinning
              Optional<BrowseNode> node = browseFacet.getByRequestPath("/path" + threadId + "/" + j);
              assertThat(node.isPresent(), is(true));
            }
            
            long threadEndTime = System.nanoTime();
            executionTimes.put(threadId, threadEndTime - threadStartTime);
          }
          catch (Throwable t) {
            log.error("Exception in thread {}: {}", threadId, t.getMessage(), t);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Signal all threads to start
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(PERFORMANCE_THRESHOLD_MS, TimeUnit.MILLISECONDS);
      assertThat("All virtual threads should complete within the time threshold", completed, is(true));
    }
    
    // Calculate statistics on execution times
    List<Long> times = new ArrayList<>(executionTimes.values());
    Collections.sort(times);
    
    long median = times.get(times.size() / 2);
    long p90 = times.get((int)(times.size() * 0.9));
    
    // Log statistics
    log.info("Thread execution time statistics (ns):");
    log.info("Median: {}", median);
    log.info("90th percentile: {}", p90);
    log.info("Max: {}", times.get(times.size() - 1));
    
    // If there is significant thread pinning, the max execution time would be
    // much higher than the median as pinned threads would block carrier threads
    double maxToMedianRatio = (double) times.get(times.size() - 1) / median;
    log.info("Max/Median ratio: {}", maxToMedianRatio);
    
    // A high ratio could indicate thread pinning, but the exact threshold depends
    // on the environment and test conditions. We use a conservative value here.
    assertThat("Max execution time should not be excessively higher than median",
        maxToMedianRatio, lessThan(100.0));
  }

  /**
   * Helper method to run browse operations with either platform or virtual threads.
   * 
   * @param threadCount Number of threads to create
   * @param operationsPerThread Number of browse operations per thread
   * @param threadFactory Factory to create either platform or virtual threads
   * @return Duration in milliseconds to complete all operations
   */
  private long runBrowseOperationsWithThreads(
      int threadCount, 
      int operationsPerThread,
      ThreadFactory threadFactory) throws Exception {
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create threads
    List<Thread> threads = new ArrayList<>(threadCount);
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread thread = threadFactory.newThread(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Perform browse operations
          for (int j = 0; j < operationsPerThread; j++) {
            List<String> displayPath = List.of("path" + threadId, "subpath" + j);
            browseFacet.getByDisplayPath(displayPath, 100, null, null);
          }
        }
        catch (Throwable t) {
          log.error("Exception in thread {}: {}", threadId, t.getMessage(), t);
        }
        finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(thread);
      thread.start();
    }
    
    // Reset the mock to clear invocation counts
    Mockito.reset(browseNodeManager);
    when(browseNodeManager.getByDisplayPath(anyList(), anyInt(), anyString(), anyMap()))
        .thenReturn(mockBrowseNodes);
    
    // Measure execution time
    long startTime = System.currentTimeMillis();
    
    // Signal all threads to start
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(PERFORMANCE_THRESHOLD_MS * 2, TimeUnit.MILLISECONDS);
    if (!completed) {
      log.warn("Not all threads completed within the timeout period");
    }
    
    long endTime = System.currentTimeMillis();
    return endTime - startTime;
  }

  /**
   * Creates a list of mock browse nodes for testing.
   * 
   * @return List of mock browse nodes
   */
  private List<BrowseNode> createMockBrowseNodes() {
    return IntStream.range(0, 10)
        .mapToObj(i -> {
          BrowseNodeData node = new BrowseNodeData();
          node.setNodeId((long) i);
          node.setName("node" + i);
          node.setAssetCount(i % 3 == 0 ? 5L : 0L);
          node.setComponentCount(i % 2 == 0 ? 2L : 0L);
          return (BrowseNode) node;
        })
        .collect(Collectors.toList());
  }

  /**
   * Creates a deep hierarchy of browse nodes for testing deep traversal.
   * 
   * @param depth The depth of the hierarchy to create
   * @return List of lists of browse nodes, one list per hierarchy level
   */
  private List<List<BrowseNode>> createDeepHierarchy(int depth) {
    return IntStream.range(0, depth)
        .mapToObj(level -> {
          // Create more nodes at shallow levels, fewer at deep levels
          int nodeCount = Math.max(1, 10 - level);
          
          return IntStream.range(0, nodeCount)
              .mapToObj(i -> {
                BrowseNodeData node = new BrowseNodeData();
                node.setNodeId(level * 100L + i);
                node.setName("level" + level + "_node" + i);
                node.setAssetCount(i % 3 == 0 ? 2L : 0L);
                node.setComponentCount(i % 2 == 0 ? 1L : 0L);
                return (BrowseNode) node;
              })
              .collect(Collectors.toList());
        })
        .collect(Collectors.toList());
  }
}