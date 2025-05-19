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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.sonatype.nexus.repository.content.browse.store.BrowseNodeData;
import org.sonatype.nexus.repository.content.browse.store.BrowseNodeManager;
import org.sonatype.nexus.repository.ossindex.PackageUrlService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for repository browse operations using Java 21 Virtual Threads.
 * 
 * This test class validates that browse node generation, tree traversal, and path-based content 
 * retrieval maintain consistency and performance when executed concurrently across many virtual threads.
 */
public class ContentBrowseVirtualThreadTest
    extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 1000;
  private static final int BROWSE_DEPTH = 5;
  private static final int BROWSE_WIDTH = 10;
  private static final int BROWSE_OPERATIONS = 5000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private BrowseNodeManager browseNodeManager;
  
  @Mock
  private Repository repository;
  
  @Mock
  private PackageUrlService packageUrlService;
  
  private BrowseFacet browseFacet;
  
  private DefaultBrowseNodeGenerator nodeGenerator;
  
  private Random random = new Random();
  
  @Before
  public void setUp() throws Exception {
    // Setup the browse facet with mocks
    browseFacet = new BrowseFacetImpl(
        Collections.emptyMap(),
        Collections.emptyMap(),
        packageUrlService,
        1000);
    
    when(repository.getFormat()).thenReturn(new Format("raw") {});
    when(repository.getName()).thenReturn("virtual-thread-test-repo");
    
    browseFacet.attach(repository);
    
    // Use reflection to inject the mocked browseNodeManager
    java.lang.reflect.Field browseNodeManagerField = BrowseFacetImpl.class.getDeclaredField("browseNodeManager");
    browseNodeManagerField.setAccessible(true);
    browseNodeManagerField.set(browseFacet, browseNodeManager);
    
    // Initialize the node generator
    nodeGenerator = new DefaultBrowseNodeGenerator();
    
    // Setup mock behavior for browse operations
    setupMockBrowseNodeManager();
  }
  
  /**
   * Sets up mock behavior for the BrowseNodeManager to simulate repository browsing operations.
   */
  private void setupMockBrowseNodeManager() {
    // Create a map to store our simulated browse nodes
    Map<Long, BrowseNodeData> nodeDataMap = new ConcurrentHashMap<>();
    Map<String, Long> pathToIdMap = new ConcurrentHashMap<>();
    AtomicInteger nodeIdGenerator = new AtomicInteger(1);
    
    // Setup root node
    BrowseNodeData rootNode = new BrowseNodeData();
    rootNode.setNodeId(0L);
    rootNode.setParentId(null);
    rootNode.setName("");
    rootNode.setPath("");
    nodeDataMap.put(0L, rootNode);
    pathToIdMap.put("", 0L);
    
    // Mock getChildNodes to return nodes based on our simulated repository
    when(browseNodeManager.getChildNodes(anyLong())).thenAnswer(invocation -> {
      Long parentId = invocation.getArgument(0);
      BrowseNodeData parent = nodeDataMap.get(parentId);
      if (parent == null) {
        return Collections.emptyList();
      }
      
      // Generate child nodes on demand
      String parentPath = parent.getPath();
      int depth = parentPath.isEmpty() ? 0 : parentPath.split("/").length;
      
      if (depth >= BROWSE_DEPTH) {
        return Collections.emptyList();
      }
      
      List<BrowseNode> children = new ArrayList<>();
      for (int i = 0; i < BROWSE_WIDTH; i++) {
        String childName = "folder-" + i;
        String childPath = parentPath.isEmpty() ? childName : parentPath + "/" + childName;
        
        // Create the node if it doesn't exist
        if (!pathToIdMap.containsKey(childPath)) {
          long nodeId = nodeIdGenerator.getAndIncrement();
          BrowseNodeData childNode = new BrowseNodeData();
          childNode.setNodeId(nodeId);
          childNode.setParentId(parentId);
          childNode.setName(childName);
          childNode.setPath(childPath);
          nodeDataMap.put(nodeId, childNode);
          pathToIdMap.put(childPath, nodeId);
        }
        
        Long nodeId = pathToIdMap.get(childPath);
        BrowseNodeData childNode = nodeDataMap.get(nodeId);
        children.add(childNode);
      }
      
      return children;
    });
    
    // Mock getNodeByPath to return nodes based on path
    when(browseNodeManager.getNodeByPath(anyString())).thenAnswer(invocation -> {
      String path = invocation.getArgument(0);
      Long nodeId = pathToIdMap.get(path);
      return nodeId != null ? nodeDataMap.get(nodeId) : null;
    });
    
    // Mock getNodeParents to return parent nodes
    when(browseNodeManager.getNodeParents(anyLong())).thenAnswer(invocation -> {
      Long nodeId = invocation.getArgument(0);
      BrowseNodeData node = nodeDataMap.get(nodeId);
      if (node == null || node.getParentId() == null) {
        return Collections.emptyList();
      }
      
      BrowseNodeData parent = nodeDataMap.get(node.getParentId());
      return parent != null ? List.of(parent) : Collections.emptyList();
    });
    
    // Mock deleteByAssetIdAndPath
    when(browseNodeManager.deleteByAssetIdAndPath(anyInt(), anyString())).thenReturn(0L);
    
    // Mock delete
    doAnswer(invocation -> null).when(browseNodeManager).delete(anyLong());
  }
  
  /**
   * Creates a mock asset with the given path and optional component.
   */
  private Asset createAsset(String path, Component component) {
    Asset asset = mock(Asset.class);
    when(asset.path()).thenReturn(path);
    when(asset.component()).thenReturn(java.util.Optional.ofNullable(component));
    EntityId entityId = mock(EntityId.class);
    when(asset.assetId()).thenReturn(entityId);
    return asset;
  }
  
  /**
   * Creates a mock asset with the given path and no component.
   */
  private Asset createAsset(String path) {
    return createAsset(path, null);
  }
  
  /**
   * Creates a mock component with the given name, namespace, and version.
   */
  private Component createComponent(String name, String namespace, String version) {
    Component component = mock(Component.class);
    when(component.name()).thenReturn(name);
    when(component.namespace()).thenReturn(namespace);
    when(component.version()).thenReturn(version);
    return component;
  }
  
  /**
   * Tests concurrent browse operations using virtual threads.
   * 
   * This test validates that browse operations can be performed concurrently
   * by many virtual threads without issues.
   */
  @Test
  public void testConcurrentBrowseWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit tasks to browse the repository concurrently
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Perform random browse operations
            for (int j = 0; j < BROWSE_OPERATIONS / VIRTUAL_THREAD_COUNT; j++) {
              // Choose a random browse depth
              int depth = random.nextInt(BROWSE_DEPTH);
              StringBuilder path = new StringBuilder();
              
              // Build a random path
              for (int k = 0; k < depth; k++) {
                if (k > 0) {
                  path.append("/");
                }
                path.append("folder-").append(random.nextInt(BROWSE_WIDTH));
              }
              
              // Get browse nodes at this path
              String browsePath = path.toString();
              BrowseNodeData node = browseNodeManager.getNodeByPath(browsePath);
              
              if (node != null) {
                // Browse children
                List<BrowseNode> children = browseNodeManager.getChildNodes(node.getNodeId());
                
                // Verify children
                assertThat("Children should not be null", children, is(notNullValue()));
                
                // If not at max depth, should have children
                if (depth < BROWSE_DEPTH - 1) {
                  assertThat("Should have expected number of children", 
                      children.size(), is(equalTo(BROWSE_WIDTH)));
                }
              }
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread browse operation", e);
            throw new RuntimeException(e);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete within timeout", completed, is(true));
      
      // Check for any exceptions
      for (Future<?> future : futures) {
        future.get(); // Will throw an exception if the task failed
      }
    }
    
    // Verify browse operations were performed
    verify(browseNodeManager, times(BROWSE_OPERATIONS)).getNodeByPath(anyString());
  }
  
  /**
   * Tests the performance of browse path generation with virtual threads.
   * 
   * This test compares the performance of computing browse paths using
   * virtual threads versus platform threads.
   */
  @Test
  public void testBrowsePathGenerationPerformance() throws Exception {
    // Create test assets and components
    List<Asset> assets = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      Component component = createComponent("component-" + i, "namespace-" + (i % 10), "1.0." + i);
      Asset asset = createAsset("path/to/asset-" + i, component);
      assets.add(asset);
    }
    
    // Test with platform threads
    long platformThreadTime = measureBrowsePathGeneration(assets, false);
    log.info("Platform thread time: {} ms", platformThreadTime);
    
    // Test with virtual threads
    long virtualThreadTime = measureBrowsePathGeneration(assets, true);
    log.info("Virtual thread time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound operations
    // but for pure computation, they might be similar or slightly slower
    // The key benefit is scalability with many concurrent operations
    assertThat("Virtual thread performance should be reasonable compared to platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 2)); // Allow some overhead for virtual threads
  }
  
  /**
   * Measures the time taken to generate browse paths for assets using either
   * platform threads or virtual threads.
   * 
   * @param assets the assets to generate browse paths for
   * @param useVirtualThreads whether to use virtual threads
   * @return the time taken in milliseconds
   */
  private long measureBrowsePathGeneration(List<Asset> assets, boolean useVirtualThreads) throws Exception {
    int threadCount = Math.min(assets.size(), 100); // Use up to 100 threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create appropriate executor
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(threadCount);
    
    try {
      // Partition assets among threads
      List<List<Asset>> partitions = partitionList(assets, threadCount);
      List<Future<?>> futures = new ArrayList<>();
      
      // Start timing
      long startTime = System.nanoTime();
      
      // Submit tasks
      for (List<Asset> partition : partitions) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Generate browse paths for each asset
            for (Asset asset : partition) {
              List<BrowsePath> assetPaths = nodeGenerator.computeAssetPaths(asset);
              List<BrowsePath> componentPaths = nodeGenerator.computeComponentPaths(asset);
              
              // Verify paths were generated correctly
              assertThat("Asset paths should not be empty", assetPaths, hasSize(greaterThan(0)));
              if (asset.component().isPresent()) {
                assertThat("Component paths should not be empty", componentPaths, hasSize(greaterThan(0)));
              }
            }
          }
          catch (Exception e) {
            log.error("Error generating browse paths", e);
            throw new RuntimeException(e);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All threads should complete within timeout", completed, is(true));
      
      // Check for any exceptions
      for (Future<?> future : futures) {
        future.get(); // Will throw an exception if the task failed
      }
      
      // Calculate elapsed time
      long endTime = System.nanoTime();
      return MILLISECONDS.convert(endTime - startTime, TimeUnit.NANOSECONDS);
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that browse operations don't cause thread pinning with virtual threads.
   * 
   * Thread pinning occurs when a virtual thread is pinned to its carrier thread,
   * preventing the carrier thread from being used by other virtual threads.
   * This can happen with synchronized blocks or native methods.
   */
  @Test
  public void testBrowseOperationsAvoidThreadPinning() throws Exception {
    // Create a virtual thread executor with limited carrier threads
    // This will make thread pinning more obvious if it occurs
    System.setProperty("jdk.virtualThreadScheduler.parallelism", "4");
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
      List<Future<?>> futures = new ArrayList<>();
      
      // Track start and end times to detect potential pinning
      long[] startTimes = new long[VIRTUAL_THREAD_COUNT];
      long[] endTimes = new long[VIRTUAL_THREAD_COUNT];
      
      // Submit tasks to browse the repository concurrently
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadIndex = i;
        futures.add(executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            startTimes[threadIndex] = System.nanoTime();
            
            // Perform browse operations that could potentially cause pinning
            String path = "folder-" + (threadIndex % BROWSE_WIDTH);
            BrowseNodeData node = browseNodeManager.getNodeByPath(path);
            
            if (node != null) {
              // Browse children and perform operations that might cause pinning
              List<BrowseNode> children = browseNodeManager.getChildNodes(node.getNodeId());
              
              // Simulate some work with the browse results
              Thread.sleep(10); // Small delay to simulate work
              
              // Delete operations
              if (threadIndex % 10 == 0) { // Only some threads perform delete
                browseFacet.deleteByAssetIdAndPath(threadIndex, path);
              }
            }
            
            endTimes[threadIndex] = System.nanoTime();
          }
          catch (Exception e) {
            log.error("Error in virtual thread browse operation", e);
            throw new RuntimeException(e);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete within timeout", completed, is(true));
      
      // Check for any exceptions
      for (Future<?> future : futures) {
        future.get(); // Will throw an exception if the task failed
      }
      
      // Analyze timing data to detect potential pinning
      // If threads are pinned, we'd see sequential execution patterns
      // rather than concurrent execution
      List<Duration> durations = new ArrayList<>();
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        durations.add(Duration.ofNanos(endTimes[i] - startTimes[i]));
      }
      
      // Sort durations to analyze distribution
      Collections.sort(durations);
      
      // Calculate percentiles
      Duration median = durations.get(durations.size() / 2);
      Duration p90 = durations.get((int)(durations.size() * 0.9));
      Duration p99 = durations.get((int)(durations.size() * 0.99));
      
      log.info("Thread execution time statistics:");
      log.info("  Median: {} ms", median.toMillis());
      log.info("  90th percentile: {} ms", p90.toMillis());
      log.info("  99th percentile: {} ms", p99.toMillis());
      
      // If there's significant thread pinning, the p99 would be much higher than median
      // as threads would be waiting for pinned carrier threads
      assertThat("99th percentile should not be excessively higher than median, which would indicate thread pinning",
          p99.toMillis(), lessThan(median.toMillis() * 10));
    }
    finally {
      System.clearProperty("jdk.virtualThreadScheduler.parallelism");
    }
  }
  
  /**
   * Tests deep browse hierarchy traversal with virtual threads.
   * 
   * This test validates that deep browse hierarchies can be efficiently
   * traversed using virtual threads without excessive overhead.
   */
  @Test
  public void testDeepBrowseHierarchyTraversal() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(100);
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit tasks to traverse deep hierarchies
      for (int i = 0; i < 100; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Start at root and traverse to a leaf node
            Long nodeId = 0L; // Root node
            int depth = 0;
            
            while (depth < BROWSE_DEPTH) {
              List<BrowseNode> children = browseNodeManager.getChildNodes(nodeId);
              if (children.isEmpty()) {
                break;
              }
              
              // Select a random child to traverse
              BrowseNode child = children.get(random.nextInt(children.size()));
              nodeId = ((BrowseNodeData) child).getNodeId();
              depth++;
              
              // Verify we can get parent nodes (traversing back up)
              List<BrowseNode> parents = browseNodeManager.getNodeParents(nodeId);
              if (depth > 0) {
                assertThat("Should have a parent node", parents, hasSize(1));
              }
            }
            
            assertThat("Should reach expected depth", depth, is(equalTo(BROWSE_DEPTH)));
          }
          catch (Exception e) {
            log.error("Error in deep hierarchy traversal", e);
            throw new RuntimeException(e);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete within timeout", completed, is(true));
      
      // Check for any exceptions
      for (Future<?> future : futures) {
        future.get(); // Will throw an exception if the task failed
      }
    }
  }
  
  /**
   * Partitions a list into approximately equal-sized sublists.
   * 
   * @param list the list to partition
   * @param partitions the number of partitions to create
   * @return a list of partitioned sublists
   */
  private <T> List<List<T>> partitionList(List<T> list, int partitions) {
    int size = list.size();
    int partitionSize = (size + partitions - 1) / partitions; // Ceiling division
    
    return IntStream.range(0, partitions)
        .mapToObj(i -> {
          int start = i * partitionSize;
          int end = Math.min(start + partitionSize, size);
          return start < end ? list.subList(start, end) : Collections.<T>emptyList();
        })
        .filter(partition -> !partition.isEmpty())
        .collect(Collectors.toList());
  }
}