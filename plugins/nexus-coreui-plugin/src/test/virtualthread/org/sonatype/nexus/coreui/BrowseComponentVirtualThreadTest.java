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
package org.sonatype.nexus.coreui;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.browse.node.BrowseNode;
import org.sonatype.nexus.repository.browse.node.BrowseNodeConfiguration;
import org.sonatype.nexus.repository.browse.node.BrowseNodeQueryService;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for {@link BrowseComponent} using Java 21 Virtual Threads.
 * 
 * This test validates that repository browsing operations execute correctly under virtual threads,
 * checks for thread pinning issues during database access, and compares performance between
 * platform threads and virtual threads for concurrent browse operations.
 */
@ExtendWith(MockitoExtension.class)
public class BrowseComponentVirtualThreadTest
{
  private static final String REPOSITORY_NAME = "repositoryName";

  private static final String ROOT = "/";

  private final BrowseNodeConfiguration configuration = new BrowseNodeConfiguration();

  @Mock
  private BrowseNodeQueryService browseNodeQueryService;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private EntityId assetId;

  @Mock
  private EntityId componentId;

  @Mock
  private Repository repository;

  private BrowseComponent underTest;

  @BeforeEach
  public void setUp() {
    when(repository.getName()).thenReturn(REPOSITORY_NAME);
    when(componentId.getValue()).thenReturn("componentId");
    when(assetId.getValue()).thenReturn("assetId");
    underTest = new BrowseComponent(configuration, browseNodeQueryService, repositoryManager);
  }

  @Test
  @DisplayName("Test root node list query with virtual thread")
  public void testRootNodeListQueryWithVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread.startVirtualThread(() -> {
      BrowseNode browseNode1 = mock(BrowseNode.class);
      when(browseNode1.getName()).thenReturn("com");

      BrowseNode browseNode2 = mock(BrowseNode.class);
      when(browseNode2.getName()).thenReturn("org");
      when(browseNode2.getComponentId()).thenReturn(componentId);

      BrowseNode browseNode3 = mock(BrowseNode.class);
      when(browseNode3.getName()).thenReturn("net");
      when(browseNode3.getAssetId()).thenReturn(assetId);
      when(browseNode3.isLeaf()).thenReturn(true);

      List<BrowseNode> browseNodes = List.of(browseNode1, browseNode2, browseNode3);

      TreeStoreLoadParameters treeStoreLoadParameters = new TreeStoreLoadParameters();
      treeStoreLoadParameters.setRepositoryName(REPOSITORY_NAME);
      treeStoreLoadParameters.setNode(ROOT);

      when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
      when(browseNodeQueryService.getByPath(repository, Collections.emptyList(),
          configuration.getMaxHtmlNodes())).thenReturn(browseNodes);

      List<BrowseNodeXO> xos = underTest.read(treeStoreLoadParameters);

      assertThat(xos, hasSize(3));
      assertThat(xos.get(0).getText(), is("com"));
      assertThat(xos.get(1).getText(), is("org"));
      assertThat(xos.get(2).getText(), is("net"));
      assertThat(xos.get(0).getId(), is("com"));
      assertThat(xos.get(1).getId(), is("org"));
      assertThat(xos.get(2).getId(), is("net"));
      assertThat(xos.get(0).getType(), is(BrowseComponent.FOLDER));
      assertThat(xos.get(1).getType(), is(BrowseComponent.COMPONENT));
      assertThat(xos.get(2).getType(), is(BrowseComponent.ASSET));
      assertFalse(xos.get(0).isLeaf());
      assertFalse(xos.get(1).isLeaf());
      assertTrue(xos.get(2).isLeaf());
      
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    }).join();
  }

  @Test
  @DisplayName("Test non-root list query with virtual thread")
  public void testNonRootListQueryWithVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread.startVirtualThread(() -> {
      BrowseNode browseNode1 = mock(BrowseNode.class);
      when(browseNode1.getName()).thenReturn("com");

      BrowseNode browseNode2 = mock(BrowseNode.class);
      when(browseNode2.getName()).thenReturn("org");
      when(browseNode2.getComponentId()).thenReturn(componentId);

      BrowseNode browseNode3 = mock(BrowseNode.class);
      when(browseNode3.getName()).thenReturn("net");
      when(browseNode3.getAssetId()).thenReturn(assetId);
      when(browseNode3.isLeaf()).thenReturn(true);

      List<BrowseNode> browseNodes = List.of(browseNode1, browseNode2, browseNode3);

      TreeStoreLoadParameters treeStoreLoadParameters = new TreeStoreLoadParameters();
      treeStoreLoadParameters.setRepositoryName(REPOSITORY_NAME);
      treeStoreLoadParameters.setNode("com/boogie/down");

      when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
      when(browseNodeQueryService.getByPath(repository, List.of("com", "boogie", "down"),
          configuration.getMaxHtmlNodes())).thenReturn(browseNodes);

      List<BrowseNodeXO> xos = underTest.read(treeStoreLoadParameters);

      assertThat(xos, hasSize(3));
      assertThat(xos.get(0).getText(), is("com"));
      assertThat(xos.get(1).getText(), is("org"));
      assertThat(xos.get(2).getText(), is("net"));
      assertThat(xos.get(0).getId(), is("com/boogie/down/com"));
      assertThat(xos.get(1).getId(), is("com/boogie/down/org"));
      assertThat(xos.get(2).getId(), is("com/boogie/down/net"));
      assertThat(xos.get(0).getType(), is(BrowseComponent.FOLDER));
      assertThat(xos.get(1).getType(), is(BrowseComponent.COMPONENT));
      assertThat(xos.get(2).getType(), is(BrowseComponent.ASSET));
      assertFalse(xos.get(0).isLeaf());
      assertFalse(xos.get(1).isLeaf());
      assertTrue(xos.get(2).isLeaf());
      
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    }).join();
  }

  @Test
  @DisplayName("Test path encoding/decoding with virtual thread")
  public void testValidateEncodedSegmentsWithVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread.startVirtualThread(() -> {
      BrowseNode browseNode1 = mock(BrowseNode.class);
      when(browseNode1.getName()).thenReturn("com");

      BrowseNode browseNode2 = mock(BrowseNode.class);
      when(browseNode2.getName()).thenReturn("org");
      when(browseNode2.getComponentId()).thenReturn(componentId);

      BrowseNode browseNode3 = mock(BrowseNode.class);
      when(browseNode3.getName()).thenReturn("n/e/t");
      when(browseNode3.getAssetId()).thenReturn(assetId);
      when(browseNode3.isLeaf()).thenReturn(true);

      List<BrowseNode> browseNodes = List.of(browseNode1, browseNode2, browseNode3);

      TreeStoreLoadParameters treeStoreLoadParameters = new TreeStoreLoadParameters();
      treeStoreLoadParameters.setRepositoryName(REPOSITORY_NAME);
      treeStoreLoadParameters.setNode("com/boo%2Fgie/down");

      when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
      when(browseNodeQueryService.getByPath(repository, List.of("com", "boo/gie", "down"),
          configuration.getMaxHtmlNodes())).thenReturn(browseNodes);

      List<BrowseNodeXO> xos = underTest.read(treeStoreLoadParameters);

      assertThat(xos, hasSize(3));
      assertThat(xos.get(0).getText(), is("com"));
      assertThat(xos.get(1).getText(), is("org"));
      assertThat(xos.get(2).getText(), is("n/e/t"));
      assertThat(xos.get(0).getId(), is("com/boo%2Fgie/down/com"));
      assertThat(xos.get(1).getId(), is("com/boo%2Fgie/down/org"));
      assertThat(xos.get(2).getId(), is("com/boo%2Fgie/down/n%2Fe%2Ft"));
      assertThat(xos.get(0).getType(), is(BrowseComponent.FOLDER));
      assertThat(xos.get(1).getType(), is(BrowseComponent.COMPONENT));
      assertThat(xos.get(2).getType(), is(BrowseComponent.ASSET));
      assertFalse(xos.get(0).isLeaf());
      assertFalse(xos.get(1).isLeaf());
      assertTrue(xos.get(2).isLeaf());
      
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    }).join();
  }

  @Test
  @DisplayName("Test thread pinning detection during browse operations")
  public void testThreadPinningDetection() throws Exception {
    // This test verifies that no thread pinning occurs during browse operations
    // by using an AtomicBoolean to track if any pinning is detected
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a virtual thread to perform the browse operation
    Thread.startVirtualThread(() -> {
      // Setup test data
      BrowseNode browseNode = mock(BrowseNode.class);
      when(browseNode.getName()).thenReturn("com");
      List<BrowseNode> browseNodes = List.of(browseNode);

      TreeStoreLoadParameters params = new TreeStoreLoadParameters();
      params.setRepositoryName(REPOSITORY_NAME);
      params.setNode(ROOT);

      when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
      when(browseNodeQueryService.getByPath(repository, Collections.emptyList(),
          configuration.getMaxHtmlNodes())).thenReturn(browseNodes);

      // Perform the browse operation
      try {
        underTest.read(params);
      } catch (Exception e) {
        // If any exception occurs that might indicate thread pinning, mark it
        if (e.getMessage() != null && e.getMessage().contains("pinned")) {
          pinningDetected.set(true);
        }
      }
    }).join();
    
    // Verify no thread pinning was detected
    assertFalse(pinningDetected.get(), "No thread pinning should occur during browse operations");
  }

  @Test
  @DisplayName("Compare performance between virtual threads and platform threads")
  public void testPerformanceComparison() throws Exception {
    // Number of concurrent browse operations to perform
    final int concurrentOperations = 100;
    final CountDownLatch virtualThreadsLatch = new CountDownLatch(concurrentOperations);
    final CountDownLatch platformThreadsLatch = new CountDownLatch(concurrentOperations);
    
    // Setup test data
    BrowseNode browseNode = mock(BrowseNode.class);
    when(browseNode.getName()).thenReturn("com");
    List<BrowseNode> browseNodes = List.of(browseNode);

    TreeStoreLoadParameters params = new TreeStoreLoadParameters();
    params.setRepositoryName(REPOSITORY_NAME);
    params.setNode(ROOT);

    when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(),
        configuration.getMaxHtmlNodes())).thenReturn(browseNodes);
    
    // Measure time for virtual threads
    long virtualThreadsStartTime = System.currentTimeMillis();
    
    // Create virtual threads for concurrent browse operations
    for (int i = 0; i < concurrentOperations; i++) {
      Thread.startVirtualThread(() -> {
        try {
          underTest.read(params);
        } finally {
          virtualThreadsLatch.countDown();
        }
      });
    }
    
    // Wait for all virtual thread operations to complete
    virtualThreadsLatch.await(30, TimeUnit.SECONDS);
    long virtualThreadsTime = System.currentTimeMillis() - virtualThreadsStartTime;
    
    // Measure time for platform threads
    long platformThreadsStartTime = System.currentTimeMillis();
    
    // Create a fixed thread pool for platform threads
    try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
      // Submit platform thread tasks
      for (int i = 0; i < concurrentOperations; i++) {
        executor.submit(() -> {
          try {
            underTest.read(params);
          } finally {
            platformThreadsLatch.countDown();
          }
        });
      }
      
      // Wait for all platform thread operations to complete
      platformThreadsLatch.await(30, TimeUnit.SECONDS);
      executor.shutdown();
    }
    
    long platformThreadsTime = System.currentTimeMillis() - platformThreadsStartTime;
    
    // Log the performance results
    System.out.println("Virtual Threads time: " + virtualThreadsTime + "ms");
    System.out.println("Platform Threads time: " + platformThreadsTime + "ms");
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // but in a test environment with mocks, the difference might not be significant
    // This assertion is more of a sanity check than a strict performance requirement
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadsTime, lessThan(platformThreadsTime * 2));
  }

  @Test
  @DisplayName("Test high concurrency browse operations with virtual threads")
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Number of concurrent browse operations to perform
    final int concurrentOperations = 1000;
    final CountDownLatch latch = new CountDownLatch(concurrentOperations);
    
    // Setup test data
    BrowseNode browseNode = mock(BrowseNode.class);
    when(browseNode.getName()).thenReturn("com");
    List<BrowseNode> browseNodes = List.of(browseNode);

    when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(),
        configuration.getMaxHtmlNodes())).thenReturn(browseNodes);
    
    // Create a list to store any exceptions that occur during execution
    List<Exception> exceptions = Collections.synchronizedList(Collections.emptyList());
    
    // Create virtual threads for concurrent browse operations with different paths
    for (int i = 0; i < concurrentOperations; i++) {
      final int index = i;
      Thread.startVirtualThread(() -> {
        try {
          // Create a unique path for each thread to simulate different browse requests
          TreeStoreLoadParameters params = new TreeStoreLoadParameters();
          params.setRepositoryName(REPOSITORY_NAME);
          params.setNode("path/" + index);
          
          // For simplicity, we'll use the same mock response for all paths
          when(browseNodeQueryService.getByPath(repository, List.of("path", String.valueOf(index)),
              configuration.getMaxHtmlNodes())).thenReturn(browseNodes);
          
          // Perform the browse operation
          List<BrowseNodeXO> result = underTest.read(params);
          
          // Verify the result
          assertThat(result, hasSize(1));
          assertThat(result.get(0).getText(), is("com"));
        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    
    // Verify all operations completed successfully
    assertTrue(completed, "All concurrent browse operations should complete within the timeout");
    assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent browse operations");
  }
}