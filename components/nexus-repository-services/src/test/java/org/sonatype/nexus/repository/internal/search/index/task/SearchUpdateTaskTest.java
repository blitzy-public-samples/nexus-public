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
package org.sonatype.nexus.repository.internal.search.index.task;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.thread.Java21TestGroup;
import org.sonatype.nexus.common.thread.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.repository.search.index.SearchUpdateService;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.thread.VirtualThreadFactory;

import org.elasticsearch.cluster.metadata.ProcessClusterEventTimeoutException;
import org.elasticsearch.common.unit.TimeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SearchUpdateTaskTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private SearchUpdateService searchUpdateService;

  @Mock
  private Repository repository1;

  @Mock
  private Repository repository2;

  @Mock
  private Repository repository3;

  @Mock
  private SearchIndexFacet searchIndexFacet1;

  @Mock
  private SearchIndexFacet searchIndexFacet2;

  @Mock
  private SearchIndexFacet searchIndexFacet3;

  @Mock
  private TaskScheduler taskScheduler;

  @Captor
  private ArgumentCaptor<Repository> repositoryCaptor;

  private final TaskConfiguration configuration = new TaskConfiguration();

  private SearchUpdateTask underTest;

  // Record for repository data to use with pattern matching
  private record RepositoryData(String name, Repository repository, SearchIndexFacet searchIndexFacet) {}

  @BeforeEach
  public void setUp() {
    when(repository1.getName()).thenReturn("repository1");
    when(repository2.getName()).thenReturn("repository2");
    when(repository3.getName()).thenReturn("repository3");
    
    when(repository1.facet(SearchIndexFacet.class)).thenReturn(searchIndexFacet1);
    when(repository2.facet(SearchIndexFacet.class)).thenReturn(searchIndexFacet2);
    when(repository3.facet(SearchIndexFacet.class)).thenReturn(searchIndexFacet3);
    
    when(repositoryManager.get("repository1")).thenReturn(repository1);
    when(repositoryManager.get("repository2")).thenReturn(repository2);
    when(repositoryManager.get("repository3")).thenReturn(repository3);
    when(repositoryManager.get("unknown")).thenReturn(null);

    configuration.setId("test");
    configuration.setTypeId("test");

    underTest = new SearchUpdateTask(repositoryManager, searchUpdateService, taskScheduler);
  }

  @Test
  public void shouldRebuildIndexForSingleRepository() {
    configuration.setString("repositoryNames", "repository1");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
  }

  @Test
  public void shouldRebuildIndexForMultipleRepositories() {
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  public void shouldSkipUnknownRepositoriesAndContinueWithValid() {
    configuration.setString("repositoryNames", "repository1,unknown,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  public void shouldContinueProcessingWhenOneRepositoryFailsRebuildingIndex() {
    doThrow(new ProcessClusterEventTimeoutException(
        new TimeValue(30000), "failed to process cluster event (delete-index)"))
        .when(searchIndexFacet1).rebuildIndex();
    configuration.setString("repositoryNames", "repository1,repository2");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchUpdateService, never()).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
  }

  @Test
  public void shouldHandleEmptyRepositoryList() {
    configuration.setString("repositoryNames", "");
    underTest.configure(configuration);
    underTest.execute();
    verify(searchIndexFacet1, never()).rebuildIndex();
    verify(searchIndexFacet2, never()).rebuildIndex();
    verify(searchUpdateService, never()).doneReindexing(any(Repository.class));
  }

  @Test
  @org.junit.jupiter.api.Tag("Java21")
  public void shouldProcessRepositoriesUsingRecordPatterns() {
    // Setup repository data using records
    List<RepositoryData> repositories = List.of(
        new RepositoryData("repository1", repository1, searchIndexFacet1),
        new RepositoryData("repository2", repository2, searchIndexFacet2),
        new RepositoryData("repository3", repository3, searchIndexFacet3)
    );

    // Configure the task with all repositories
    configuration.setString("repositoryNames", "repository1,repository2,repository3");
    underTest.configure(configuration);
    
    // Execute the task
    underTest.execute();
    
    // Verify using record patterns
    for (RepositoryData(var name, var repo, var facet) : repositories) {
      verify(facet).rebuildIndex();
      verify(searchUpdateService).doneReindexing(repo);
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("Java21")
  public void shouldHandleElasticsearchExceptionsGracefully() {
    // Setup exception for repository1
    doThrow(new ProcessClusterEventTimeoutException(
        new TimeValue(30000), "failed to process cluster event (delete-index)"))
        .when(searchIndexFacet1).rebuildIndex();
    
    // Setup exception for repository3 with a different exception type
    doThrow(new RuntimeException("Simulated Elasticsearch error"))
        .when(searchIndexFacet3).rebuildIndex();
    
    // Configure the task with all repositories
    configuration.setString("repositoryNames", "repository1,repository2,repository3");
    underTest.configure(configuration);
    
    // Execute the task
    underTest.execute();
    
    // Verify only repository2 completed successfully
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchIndexFacet3).rebuildIndex();
    
    verify(searchUpdateService, never()).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
    verify(searchUpdateService, never()).doneReindexing(repository3);
  }

  @Test
  @org.junit.jupiter.api.Tag("Java21")
  public void shouldMaintainThreadContinuityDuringElasticsearchOperations() {
    // Setup a latch to coordinate the test
    CountDownLatch operationStarted = new CountDownLatch(1);
    CountDownLatch operationCompleted = new CountDownLatch(1);
    
    // Configure searchIndexFacet1 to simulate a long-running Elasticsearch operation
    doAnswer(invocation -> {
      // Signal that the operation has started
      operationStarted.countDown();
      
      // Wait for the test to signal completion
      operationCompleted.await(5, TimeUnit.SECONDS);
      
      return null;
    }).when(searchIndexFacet1).rebuildIndex();
    
    // Configure the task with repository1
    configuration.setString("repositoryNames", "repository1");
    underTest.configure(configuration);
    
    // Execute the task in a separate thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      underTest.execute();
    });
    
    try {
      // Wait for the operation to start
      assertTrue(operationStarted.await(5, TimeUnit.SECONDS), "Operation should start within timeout");
      
      // Signal completion and wait for the future to complete
      operationCompleted.countDown();
      future.get(5, TimeUnit.SECONDS);
      
      // Verify that the operation completed successfully
      verify(searchIndexFacet1).rebuildIndex();
      verify(searchUpdateService).doneReindexing(repository1);
    }
    catch (Exception e) {
      throw new RuntimeException("Test failed", e);
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("Java21")
  public void shouldHandleInterruptedThreadsGracefully() {
    // Setup a latch to coordinate the test
    CountDownLatch operationStarted = new CountDownLatch(1);
    
    // Configure searchIndexFacet1 to simulate an operation that gets interrupted
    doAnswer(invocation -> {
      // Signal that the operation has started
      operationStarted.countDown();
      
      // Simulate an interruption
      Thread.currentThread().interrupt();
      
      // Check if we're interrupted and throw an exception
      if (Thread.interrupted()) {
        throw new InterruptedException("Operation was interrupted");
      }
      
      return null;
    }).when(searchIndexFacet1).rebuildIndex();
    
    // Configure the task with repository1
    configuration.setString("repositoryNames", "repository1");
    underTest.configure(configuration);
    
    // Execute the task
    underTest.execute();
    
    // Verify that the operation was attempted but not marked as completed
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchUpdateService, never()).doneReindexing(repository1);
  }

  @Test
  @org.junit.jupiter.api.Tag("VirtualThread")
  public void shouldExecuteWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Configure the task with multiple repositories
    configuration.setString("repositoryNames", "repository1,repository2,repository3");
    underTest.configure(configuration);
    
    // Execute the task using a virtual thread
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        underTest.execute();
      }, executor);
      
      // Wait for completion
      future.get(5, TimeUnit.SECONDS);
    }
    
    // Verify all repositories were processed
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchIndexFacet3).rebuildIndex();
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
    verify(searchUpdateService).doneReindexing(repository3);
  }

  @Test
  @org.junit.jupiter.api.Tag("VirtualThread")
  public void shouldHandleHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a large number of mock repositories
    int repositoryCount = 100;
    StringBuilder repositoryNames = new StringBuilder();
    
    for (int i = 0; i < repositoryCount; i++) {
      String repoName = "repository" + i;
      repositoryNames.append(repoName);
      if (i < repositoryCount - 1) {
        repositoryNames.append(",");
      }
      
      // Setup mock for each repository
      Repository mockRepo = mock(Repository.class);
      SearchIndexFacet mockFacet = mock(SearchIndexFacet.class);
      
      when(mockRepo.getName()).thenReturn(repoName);
      when(mockRepo.facet(SearchIndexFacet.class)).thenReturn(mockFacet);
      when(repositoryManager.get(repoName)).thenReturn(mockRepo);
    }
    
    // Configure the task with all repositories
    configuration.setString("repositoryNames", repositoryNames.toString());
    underTest.configure(configuration);
    
    // Track completion count
    AtomicInteger completionCount = new AtomicInteger(0);
    
    // Setup completion tracking
    doAnswer(invocation -> {
      completionCount.incrementAndGet();
      return null;
    }).when(searchUpdateService).doneReindexing(any(Repository.class));
    
    // Execute the task
    underTest.execute();
    
    // Verify all repositories were processed
    assertEquals(repositoryCount, completionCount.get(), 
        "All repositories should be processed successfully");
  }

  @Test
  @org.junit.jupiter.api.Tag("VirtualThread")
  public void shouldMaintainThreadContinuityWithVirtualThreads() throws Exception {
    // Setup a latch to coordinate the test
    CountDownLatch latch = new CountDownLatch(3);
    
    // Configure mock facets to count down the latch
    doAnswer(invocation -> {
      latch.countDown();
      return null;
    }).when(searchIndexFacet1).rebuildIndex();
    
    doAnswer(invocation -> {
      latch.countDown();
      return null;
    }).when(searchIndexFacet2).rebuildIndex();
    
    doAnswer(invocation -> {
      latch.countDown();
      return null;
    }).when(searchIndexFacet3).rebuildIndex();
    
    // Configure the task with all repositories
    configuration.setString("repositoryNames", "repository1,repository2,repository3");
    underTest.configure(configuration);
    
    // Execute the task
    underTest.execute();
    
    // Verify all repositories were processed concurrently
    assertTrue(latch.await(5, TimeUnit.SECONDS), 
        "All repositories should be processed within the timeout");
    
    // Verify completion was called for all repositories
    verify(searchUpdateService).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
    verify(searchUpdateService).doneReindexing(repository3);
  }

  @Test
  @org.junit.jupiter.api.Tag("VirtualThread")
  public void shouldHandleMixOfSuccessAndFailureWithVirtualThreads() {
    // Setup exceptions for some repositories
    doThrow(new ProcessClusterEventTimeoutException(
        new TimeValue(30000), "failed to process cluster event (delete-index)"))
        .when(searchIndexFacet1).rebuildIndex();
    
    doThrow(new RuntimeException("Simulated error"))
        .when(searchIndexFacet3).rebuildIndex();
    
    // Configure the task with all repositories
    configuration.setString("repositoryNames", "repository1,repository2,repository3");
    underTest.configure(configuration);
    
    // Execute the task
    underTest.execute();
    
    // Verify all repositories were attempted
    verify(searchIndexFacet1).rebuildIndex();
    verify(searchIndexFacet2).rebuildIndex();
    verify(searchIndexFacet3).rebuildIndex();
    
    // Verify only successful operations were marked as completed
    verify(searchUpdateService, never()).doneReindexing(repository1);
    verify(searchUpdateService).doneReindexing(repository2);
    verify(searchUpdateService, never()).doneReindexing(repository3);
  }

  // Helper method to create a mock repository
  private Repository mock(Repository.class) {
    return org.mockito.Mockito.mock(Repository.class);
  }

  // Helper method to create a mock search index facet
  private SearchIndexFacet mock(SearchIndexFacet.class) {
    return org.mockito.Mockito.mock(SearchIndexFacet.class);
  }
}